package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.time.DayOfWeek;
import java.time.MonthDay;
import java.util.Objects;
import java.util.Set;

public record CalendarRecurrencePattern(
        Kind kind,
        Set<DayOfWeek> weekdays,
        DayOfWeek weekStart,
        Integer monthDay,
        Integer ordinal,
        DayOfWeek weekday,
        MonthDay yearDay) {

    public enum Kind {
        DAILY,
        WEEKLY,
        MONTH_DAY,
        LAST_DAY_OF_MONTH,
        MONTHLY_NTH_WEEKDAY,
        MONTHLY_LAST_WEEKDAY,
        YEARLY
    }

    public CalendarRecurrencePattern {
        Objects.requireNonNull(kind, "kind");
        weekdays = weekdays == null ? Set.of() : Set.copyOf(weekdays);
    }

    public static CalendarRecurrencePattern daily() {
        return new CalendarRecurrencePattern(Kind.DAILY, Set.of(), null, null, null, null, null);
    }

    public static CalendarRecurrencePattern weekly(
            Set<DayOfWeek> weekdays, DayOfWeek weekStart) {
        Objects.requireNonNull(weekdays, "weekdays");
        Objects.requireNonNull(weekStart, "weekStart");
        if (weekdays.isEmpty()) {
            throw new IllegalArgumentException("Weekly recurrence requires at least one weekday");
        }
        return new CalendarRecurrencePattern(
                Kind.WEEKLY, weekdays, weekStart, null, null, null, null);
    }

    public static CalendarRecurrencePattern monthDay(int day) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("Month day must be between 1 and 31");
        }
        return new CalendarRecurrencePattern(
                Kind.MONTH_DAY, Set.of(), null, day, null, null, null);
    }

    public static CalendarRecurrencePattern lastDayOfMonth() {
        return new CalendarRecurrencePattern(
                Kind.LAST_DAY_OF_MONTH, Set.of(), null, null, null, null, null);
    }

    public static CalendarRecurrencePattern monthlyNthWeekday(
            int ordinal, DayOfWeek weekday) {
        if (ordinal < 1 || ordinal > 5) {
            throw new IllegalArgumentException("Weekday ordinal must be between 1 and 5");
        }
        return new CalendarRecurrencePattern(
                Kind.MONTHLY_NTH_WEEKDAY,
                Set.of(),
                null,
                null,
                ordinal,
                Objects.requireNonNull(weekday, "weekday"),
                null);
    }

    public static CalendarRecurrencePattern monthlyLastWeekday(DayOfWeek weekday) {
        return new CalendarRecurrencePattern(
                Kind.MONTHLY_LAST_WEEKDAY,
                Set.of(),
                null,
                null,
                null,
                Objects.requireNonNull(weekday, "weekday"),
                null);
    }

    public static CalendarRecurrencePattern yearly(MonthDay monthDay) {
        return new CalendarRecurrencePattern(
                Kind.YEARLY,
                Set.of(),
                null,
                null,
                null,
                null,
                Objects.requireNonNull(monthDay, "monthDay"));
    }
}
