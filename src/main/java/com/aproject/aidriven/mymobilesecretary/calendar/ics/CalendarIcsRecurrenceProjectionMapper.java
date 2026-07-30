package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceEnd;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrencePattern;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CalendarIcsRecurrenceProjectionMapper {

    private CalendarIcsRecurrenceProjectionMapper() {}

    public static CalendarRecurrencePattern pattern(
            String frequency,
            List<Integer> weekdays,
            Integer weekStart,
            Integer monthDay,
            Integer weekdayOrdinal,
            Integer weekday,
            Integer yearMonth,
            Integer yearDay) {
        return switch (required(frequency)) {
            case "DAILY" -> {
                requireEmpty(weekdays, weekStart, monthDay, weekdayOrdinal, weekday, yearMonth, yearDay);
                yield CalendarRecurrencePattern.daily();
            }
            case "WEEKLY" -> {
                requireEmpty(
                        monthDay,
                        weekdayOrdinal,
                        weekday,
                        yearMonth,
                        yearDay);
                yield CalendarRecurrencePattern.weekly(
                        isoDays(weekdays), isoDay(weekStart));
            }
            case "MONTHLY" -> {
                requireEmpty(weekdays, weekStart, yearMonth, yearDay);
                yield monthly(monthDay, weekdayOrdinal, weekday);
            }
            case "YEARLY" -> {
                requireEmpty(
                        weekdays,
                        weekStart,
                        monthDay,
                        weekdayOrdinal,
                        weekday);
                if (yearMonth == null || yearDay == null) {
                    throw malformed();
                }
                yield CalendarRecurrencePattern.yearly(
                        MonthDay.of(yearMonth, yearDay));
            }
            default -> throw malformed();
        };
    }

    public static CalendarRecurrenceEnd end(
            String kind,
            Integer count,
            LocalDate untilDate,
            LocalDateTime untilTimed) {
        return switch (required(kind)) {
            case "UNBOUNDED" -> {
                if (count != null || untilDate != null || untilTimed != null) {
                    throw malformed();
                }
                yield CalendarRecurrenceEnd.unbounded();
            }
            case "COUNT" -> {
                if (count == null || untilDate != null || untilTimed != null) {
                    throw malformed();
                }
                yield CalendarRecurrenceEnd.count(count);
            }
            case "UNTIL_DATE" -> {
                if (count != null || untilDate == null || untilTimed != null) {
                    throw malformed();
                }
                yield CalendarRecurrenceEnd.untilDate(untilDate);
            }
            case "UNTIL_TIMED" -> {
                if (count != null || untilDate != null || untilTimed == null) {
                    throw malformed();
                }
                yield CalendarRecurrenceEnd.untilTimed(untilTimed);
            }
            default -> throw malformed();
        };
    }

    private static CalendarRecurrencePattern monthly(
            Integer monthDay, Integer ordinal, Integer weekday) {
        if (monthDay != null) {
            if (ordinal != null || weekday != null) {
                throw malformed();
            }
            return monthDay == -1
                    ? CalendarRecurrencePattern.lastDayOfMonth()
                    : CalendarRecurrencePattern.monthDay(monthDay);
        }
        if (ordinal == null || weekday == null) {
            throw malformed();
        }
        return ordinal == -1
                ? CalendarRecurrencePattern.monthlyLastWeekday(isoDay(weekday))
                : CalendarRecurrencePattern.monthlyNthWeekday(
                        ordinal, isoDay(weekday));
    }

    private static Set<DayOfWeek> isoDays(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            throw malformed();
        }
        Set<DayOfWeek> result = values.stream()
                .map(CalendarIcsRecurrenceProjectionMapper::isoDay)
                .collect(Collectors.toUnmodifiableSet());
        if (result.size() != values.size()) {
            throw malformed();
        }
        return result;
    }

    private static DayOfWeek isoDay(Integer value) {
        if (value == null || value < 1 || value > 7) {
            throw malformed();
        }
        return DayOfWeek.of(value);
    }

    private static void requireEmpty(Object... values) {
        for (Object value : values) {
            if (value instanceof List<?> list ? !list.isEmpty() : value != null) {
                throw malformed();
            }
        }
    }

    private static String required(String value) {
        if (value == null) {
            throw malformed();
        }
        return value;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
                "Malformed recurrence persistence shape");
    }
}
