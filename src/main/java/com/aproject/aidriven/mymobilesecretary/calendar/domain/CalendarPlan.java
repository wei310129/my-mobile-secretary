package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CalendarPlan {

    private final String title;
    private final CalendarPlacement placement;
    private final List<CalendarActivity> activities = new ArrayList<>();
    private final List<CalendarTimeNode> nodes = new ArrayList<>();

    private CalendarPlan(String title, CalendarPlacement placement) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        this.title = title.trim();
        this.placement = Objects.requireNonNull(placement, "placement");
    }

    public static CalendarPlan create(String title, CalendarPlacement placement) {
        return new CalendarPlan(title, placement);
    }

    public String title() {
        return title;
    }

    public CalendarPlacement placement() {
        return placement;
    }

    public List<CalendarActivity> activities() {
        return List.copyOf(activities);
    }

    public void addNode(CalendarTimeNode node) {
        CalendarNodeCollection.add(nodes, node);
    }

    public List<CalendarTimeNode> nodes() {
        return List.copyOf(nodes);
    }

    public CalendarActivity addActivity(String title, CalendarPlacement placement) {
        var activity = new CalendarActivity(title, placement);
        activities.add(activity);
        return activity;
    }

    public boolean overlaps(CalendarPlan other) {
        Objects.requireNonNull(other, "other");
        return overlaps(placement, other.placement);
    }

    private static boolean overlaps(CalendarPlacement left, CalendarPlacement right) {
        if (left instanceof CalendarPlacement.TimedInterval leftInterval) {
            return overlaps(leftInterval, right);
        }
        if (left instanceof CalendarPlacement.TimedPoint leftPoint) {
            if (right instanceof CalendarPlacement.TimedPoint rightPoint) {
                return leftPoint.time().equals(rightPoint.time());
            }
            if (right instanceof CalendarPlacement.TimedInterval rightInterval) {
                return contains(rightInterval, leftPoint.time());
            }
        }
        if (left instanceof CalendarPlacement.AllDay leftDay
                && right instanceof CalendarPlacement.AllDay rightDay) {
            return leftDay.start().isBefore(rightDay.endExclusive())
                    && rightDay.start().isBefore(leftDay.endExclusive());
        }
        return false;
    }

    private static boolean overlaps(
            CalendarPlacement.TimedInterval interval, CalendarPlacement other) {
        if (other instanceof CalendarPlacement.TimedInterval otherInterval) {
            return interval.start().isBefore(otherInterval.end())
                    && otherInterval.start().isBefore(interval.end());
        }
        return other instanceof CalendarPlacement.TimedPoint point && contains(interval, point.time());
    }

    private static boolean contains(CalendarPlacement.TimedInterval interval, Instant point) {
        return !point.isBefore(interval.start()) && point.isBefore(interval.end());
    }
}
