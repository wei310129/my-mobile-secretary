package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CalendarTimeResolver {

    private CalendarTimeResolver() {}

    public static Map<String, Instant> resolve(
            List<CalendarTimeNode> nodes, Instant ownerStart, Instant ownerEnd) {
        var byId = nodes.stream()
                .collect(Collectors.toMap(
                        CalendarTimeNode::id, Function.identity(), (left, right) -> {
                            throw new IllegalArgumentException("Duplicate node id");
                        }));
        var result = new LinkedHashMap<String, Instant>();
        for (var node : nodes) {
            result.put(node.id(), resolve(node, byId, ownerStart, ownerEnd));
        }
        return Map.copyOf(result);
    }

    public static Instant resolveLocal(LocalDateTime localTime, ZoneId zoneId) {
        var rules = zoneId.getRules();
        var offsets = rules.getValidOffsets(localTime);
        if (offsets.isEmpty()) {
            ZoneOffsetTransition transition = rules.getTransition(localTime);
            throw new IllegalArgumentException("Local time does not exist: " + transition);
        }
        return localTime.atOffset(offsets.getFirst()).toInstant();
    }

    private static Instant resolve(
            CalendarTimeNode node,
            Map<String, CalendarTimeNode> byId,
            Instant ownerStart,
            Instant ownerEnd) {
        return switch (node.expression()) {
            case TimeExpression.Absolute absolute -> absolute.time();
            case TimeExpression.OwnerRelative relative ->
                (relative.boundary() == CalendarTimeNode.OwnerBoundary.START ? ownerStart : ownerEnd)
                        .plus(relative.offset());
            case TimeExpression.NodeRelative relative -> {
                var base = byId.get(relative.baseNodeId());
                if (base == null || base.expression() instanceof TimeExpression.NodeRelative) {
                    throw new IllegalArgumentException(
                            "Node dependency must reference an absolute or owner-boundary node");
                }
                yield resolve(base, byId, ownerStart, ownerEnd).plus(relative.offset());
            }
        };
    }
}
