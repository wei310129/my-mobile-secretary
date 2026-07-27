package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CalendarPlanTest {

    @Test
    void supportsIntervalPointAndAllDayWithoutTreatingOverlapAsInvalid() {
        var zone = ZoneId.of("Asia/Taipei");
        var first = CalendarPlan.create("逛街", CalendarPlacement.interval(
                Instant.parse("2026-07-25T03:00:00Z"), Instant.parse("2026-07-25T08:00:00Z"), zone));
        var overlapping = CalendarPlan.create("電影候選", CalendarPlacement.point(
                Instant.parse("2026-07-25T03:30:00Z"), zone));
        var allDay = CalendarPlan.create("旅行日", CalendarPlacement.allDay(
                LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 27)));

        assertThat(first.overlaps(overlapping)).isTrue();
        assertThat(first.title()).isEqualTo("逛街");
        assertThat(allDay.placement().kind()).isEqualTo(CalendarPlacement.Kind.ALL_DAY);
    }

    @Test
    void acceptsAnIntervalThatCrossesMidnight() {
        var placement = CalendarPlacement.interval(
                Instant.parse("2026-07-25T15:30:00Z"),
                Instant.parse("2026-07-25T17:30:00Z"),
                ZoneId.of("Asia/Taipei"));

        assertThat(placement.start().atZone(placement.zoneId()).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 7, 25));
        assertThat(placement.end().atZone(placement.zoneId()).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void allowsOnlyOneActivityLevelAndValidPlacementRanges() {
        var plan = CalendarPlan.create("裕隆城", CalendarPlacement.point(
                Instant.parse("2026-07-25T03:00:00Z"), ZoneId.of("Asia/Taipei")));
        var activity = plan.addActivity("電影", CalendarPlacement.point(
                Instant.parse("2026-07-25T03:30:00Z"), ZoneId.of("Asia/Taipei")));

        assertThat(plan.activities()).containsExactly(activity);
        assertThatThrownBy(() -> activity.addActivity("孫層"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CalendarPlacement.interval(
                Instant.parse("2026-07-25T04:00:00Z"),
                Instant.parse("2026-07-25T04:00:00Z"),
                ZoneId.of("Asia/Taipei")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarPlacement.allDay(
                LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planAndActivityEachOwnMultipleNodesWithoutAddingAnotherActivityLevel() {
        var plan = CalendarPlan.create("郵輪旅行", CalendarPlacement.allDay(
                LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 27)));
        var activity = plan.addActivity("登船", CalendarPlacement.point(
                Instant.parse("2026-07-25T03:30:00Z"), ZoneId.of("Asia/Taipei")));
        var planNode = CalendarTimeNode.absolute(
                "departure", "離港", Instant.parse("2026-07-25T05:00:00Z"));
        var queueNode = CalendarTimeNode.relativeToOwner(
                "queue", "排隊", CalendarTimeNode.OwnerBoundary.START,
                java.time.Duration.ofMinutes(-10), Criticality.NORMAL, Adjustability.FLEXIBLE);
        var boardingNode = CalendarTimeNode.absolute(
                "boarding", "登船", Instant.parse("2026-07-25T03:30:00Z"));

        plan.addNode(planNode);
        activity.addNode(queueNode);
        activity.addNode(boardingNode);

        assertThat(plan.nodes()).containsExactly(planNode);
        assertThat(activity.nodes()).containsExactly(queueNode, boardingNode);
        assertThatThrownBy(() -> activity.addNode(CalendarTimeNode.absolute(
                "queue", "重複", Instant.parse("2026-07-25T03:20:00Z"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
