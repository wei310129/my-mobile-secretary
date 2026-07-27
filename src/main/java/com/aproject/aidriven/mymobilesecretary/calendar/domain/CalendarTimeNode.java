package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record CalendarTimeNode(
        String id,
        String label,
        TimeExpression expression,
        Criticality criticality,
        Adjustability adjustability) {

    public enum OwnerBoundary {
        START,
        END
    }

    public CalendarTimeNode {
        if (id == null || id.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Node id and label are required");
        }
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(criticality, "criticality");
        Objects.requireNonNull(adjustability, "adjustability");
    }

    public static CalendarTimeNode absolute(String id, String label, Instant time) {
        return new CalendarTimeNode(
                id, label, new TimeExpression.Absolute(time), Criticality.NORMAL, Adjustability.LOCKED);
    }

    public static CalendarTimeNode relativeToOwner(
            String id,
            String label,
            OwnerBoundary boundary,
            Duration offset,
            Criticality criticality,
            Adjustability adjustability) {
        return new CalendarTimeNode(
                id,
                label,
                new TimeExpression.OwnerRelative(boundary, offset),
                criticality,
                adjustability);
    }

    public static CalendarTimeNode relativeToNode(
            String id,
            String label,
            String baseNodeId,
            Duration offset,
            Criticality criticality,
            Adjustability adjustability) {
        if (id != null && id.equals(baseNodeId)) {
            throw new IllegalArgumentException("Node cannot reference itself");
        }
        return new CalendarTimeNode(
                id,
                label,
                new TimeExpression.NodeRelative(baseNodeId, offset),
                criticality,
                adjustability);
    }

    public Instant absoluteTime() {
        if (expression instanceof TimeExpression.Absolute absolute) {
            return absolute.time();
        }
        throw new IllegalStateException("Node is not absolute");
    }
}
