package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarTimeNodeTest {

    @Test
    void resolvesOwnerBoundaryAndOneLevelNodeDependencyExactly() {
        var activityStart = Instant.parse("2026-07-25T03:30:00Z");
        var queue = CalendarTimeNode.relativeToOwner(
                "queue", "排隊", CalendarTimeNode.OwnerBoundary.START, Duration.ofMinutes(-10),
                Criticality.CRITICAL, Adjustability.FLEXIBLE);
        var check = CalendarTimeNode.relativeToNode(
                "check", "確認票券", "queue", Duration.ofMinutes(-5),
                Criticality.NORMAL, Adjustability.LOCKED);

        var resolved = CalendarTimeResolver.resolve(List.of(queue, check), activityStart,
                Instant.parse("2026-07-25T04:30:00Z"));

        assertThat(resolved.get("queue")).isEqualTo(Instant.parse("2026-07-25T03:20:00Z"));
        assertThat(resolved.get("check")).isEqualTo(Instant.parse("2026-07-25T03:15:00Z"));
        assertThat(queue.criticality()).isEqualTo(Criticality.CRITICAL);
        assertThat(queue.adjustability()).isEqualTo(Adjustability.FLEXIBLE);
    }

    @Test
    void recalculatesRelativeNodesWhenTheOwnerMoves() {
        var queue = CalendarTimeNode.relativeToOwner(
                "queue",
                "排隊",
                CalendarTimeNode.OwnerBoundary.START,
                Duration.ofMinutes(-10),
                Criticality.CRITICAL,
                Adjustability.FLEXIBLE);

        var moved = CalendarTimeResolver.resolve(
                List.of(queue),
                Instant.parse("2026-07-25T04:00:00Z"),
                Instant.parse("2026-07-25T05:00:00Z"));

        assertThat(moved.get("queue")).isEqualTo(Instant.parse("2026-07-25T03:50:00Z"));
        assertThat(CalendarReminderRule.before(Duration.ofMinutes(15)).resolve(moved.get("queue")))
                .isEqualTo(Instant.parse("2026-07-25T03:35:00Z"));
    }

    @Test
    void keepsCriticalityAndAdjustabilityAsIndependentDimensions() {
        for (var criticality : Criticality.values()) {
            for (var adjustability : Adjustability.values()) {
                var node = CalendarTimeNode.relativeToOwner(
                        criticality + "-" + adjustability,
                        "節點",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ZERO,
                        criticality,
                        adjustability);

                assertThat(node.criticality()).isEqualTo(criticality);
                assertThat(node.adjustability()).isEqualTo(adjustability);
            }
        }
    }

    @Test
    void rejectsSecondLevelDependencyCyclesAndUnknownBase() {
        var a = CalendarTimeNode.absolute("a", "A", Instant.parse("2026-07-25T03:00:00Z"));
        var b = CalendarTimeNode.relativeToNode("b", "B", "a", Duration.ofMinutes(5),
                Criticality.NORMAL, Adjustability.WINDOWED);
        var c = CalendarTimeNode.relativeToNode("c", "C", "b", Duration.ofMinutes(5),
                Criticality.NORMAL, Adjustability.FLEXIBLE);

        assertThatThrownBy(() -> CalendarTimeResolver.resolve(
                List.of(a, b, c), a.absoluteTime(), a.absoluteTime()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarTimeResolver.resolve(
                List.of(CalendarTimeNode.relativeToNode("x", "X", "missing", Duration.ZERO,
                        Criticality.NORMAL, Adjustability.FLEXIBLE)),
                a.absoluteTime(), a.absoluteTime()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesDstGapByRejectingAndOverlapByFirstValidOffset() {
        var zone = ZoneId.of("America/New_York");

        assertThatThrownBy(() -> CalendarTimeResolver.resolveLocal(
                LocalDateTime.of(2026, 3, 8, 2, 30), zone))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CalendarTimeResolver.resolveLocal(
                LocalDateTime.of(2026, 11, 1, 1, 30), zone))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }
}
