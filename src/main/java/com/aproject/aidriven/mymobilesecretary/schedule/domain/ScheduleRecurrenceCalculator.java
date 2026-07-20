package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/** 固定行程的純 Java 日期規則；不讓 LLM 推算下一場。 */
public final class ScheduleRecurrenceCalculator {

    private ScheduleRecurrenceCalculator() {
    }

    public static LocalDate nextDate(LocalDate current, ScheduleItem.Recurrence recurrence) {
        return switch (recurrence) {
            case NONE -> throw new IllegalArgumentException("single schedule has no next date");
            case WEEKLY -> current.plusWeeks(1);
            case WEEKDAYS -> nextWeekday(current);
            case MONTHLY_NTH_WEEKDAY -> nextMonthlyOrdinalWeekday(current);
        };
    }

    public static boolean occursOn(LocalDate anchor, LocalDate date,
                                   ScheduleItem.Recurrence recurrence) {
        if (date.isBefore(anchor)) return false;
        return switch (recurrence) {
            case NONE -> false;
            case WEEKLY -> date.getDayOfWeek() == anchor.getDayOfWeek();
            case WEEKDAYS -> isWeekday(date);
            case MONTHLY_NTH_WEEKDAY -> date.getDayOfWeek() == anchor.getDayOfWeek()
                    && ordinalInMonth(date) == ordinalInMonth(anchor);
        };
    }

    private static LocalDate nextWeekday(LocalDate current) {
        LocalDate next = current.plusDays(1);
        while (!isWeekday(next)) next = next.plusDays(1);
        return next;
    }

    private static LocalDate nextMonthlyOrdinalWeekday(LocalDate current) {
        int ordinal = ordinalInMonth(current);
        DayOfWeek weekday = current.getDayOfWeek();
        YearMonth month = YearMonth.from(current).plusMonths(1);
        while (true) {
            LocalDate candidate = month.atDay(1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday));
            if (YearMonth.from(candidate).equals(month)) return candidate;
            month = month.plusMonths(1);
        }
    }

    private static int ordinalInMonth(LocalDate date) {
        return ((date.getDayOfMonth() - 1) / 7) + 1;
    }

    private static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
