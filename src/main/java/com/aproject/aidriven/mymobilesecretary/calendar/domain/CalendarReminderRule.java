package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record CalendarReminderRule(Duration offset) {

    public CalendarReminderRule {
        Objects.requireNonNull(offset, "offset");
        if (offset.isPositive()) {
            throw new IllegalArgumentException("Calendar reminders cannot fire after a node");
        }
    }

    public static CalendarReminderRule atNode() {
        return offset(Duration.ZERO);
    }

    public static CalendarReminderRule before(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Before duration cannot be negative");
        }
        return offset(duration.negated());
    }

    public static CalendarReminderRule offset(Duration offset) {
        return new CalendarReminderRule(offset);
    }

    public Instant resolve(Instant nodeTime) {
        return nodeTime.plus(offset);
    }
}
