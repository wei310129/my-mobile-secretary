package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CalendarRecurrenceExpander {

    private static final long MAX_SCAN_DAYS = 100_000;

    private CalendarRecurrenceExpander() {}

    public static List<CalendarOccurrence> expand(
            CalendarRecurrenceRule rule,
            CalendarRecurrenceExceptions exceptions,
            CalendarRecurrenceWindow window) {
        var occurrences = new TreeMap<CalendarOccurrenceKey, CalendarOccurrence>();
        int validCount = 0;
        LocalDate date = scanStart(rule, window);
        LocalDate scanEnd = scanEnd(rule, window);
        if (ChronoUnit.DAYS.between(date, scanEnd) > MAX_SCAN_DAYS) {
            throw new IllegalArgumentException(
                    "Recurrence expansion exceeds the scan budget");
        }

        while (!date.isAfter(scanEnd)) {
            if (date.equals(rule.anchorDate()) || matches(rule, date)) {
                var candidate = createOccurrence(rule, date);
                if (candidate != null && withinEnd(rule, candidate.key())) {
                    validCount++;
                    if (window.contains(date)) {
                        occurrences.put(candidate.key(), candidate);
                    }
                    if (reachedCount(rule, validCount)) {
                        break;
                    }
                }
            }
            date = date.plusDays(1);
        }

        applyExceptions(rule, exceptions, window, occurrences);
        return occurrences.values().stream().limit(window.maxOccurrences()).toList();
    }

    private static LocalDate scanStart(
            CalendarRecurrenceRule rule, CalendarRecurrenceWindow window) {
        if (rule.end().kind() == CalendarRecurrenceEnd.Kind.COUNT) {
            return rule.anchorDate();
        }
        return rule.anchorDate().isAfter(window.startInclusive())
                ? rule.anchorDate()
                : window.startInclusive();
    }

    private static LocalDate scanEnd(
            CalendarRecurrenceRule rule, CalendarRecurrenceWindow window) {
        return switch (rule.end().kind()) {
            case UNTIL_DATE -> min(window.endExclusive().minusDays(1), rule.end().untilDate());
            case UNTIL_TIMED ->
                min(window.endExclusive().minusDays(1), rule.end().untilTimed().toLocalDate());
            case COUNT, UNBOUNDED -> window.endExclusive().minusDays(1);
        };
    }

    private static boolean reachedCount(CalendarRecurrenceRule rule, int count) {
        return rule.end().kind() == CalendarRecurrenceEnd.Kind.COUNT
                && count >= rule.end().count();
    }

    private static boolean withinEnd(
            CalendarRecurrenceRule rule, CalendarOccurrenceKey key) {
        return switch (rule.end().kind()) {
            case UNBOUNDED, COUNT -> true;
            case UNTIL_DATE -> !key.allDayStart().isAfter(rule.end().untilDate());
            case UNTIL_TIMED -> !key.timedStart().isAfter(rule.end().untilTimed());
        };
    }

    private static boolean matches(CalendarRecurrenceRule rule, LocalDate date) {
        var anchor = rule.anchorDate();
        var pattern = rule.pattern();
        return switch (pattern.kind()) {
            case DAILY -> ChronoUnit.DAYS.between(anchor, date) % rule.interval() == 0;
            case WEEKLY -> {
                LocalDate anchorWeek = anchor.with(
                        TemporalAdjusters.previousOrSame(pattern.weekStart()));
                LocalDate candidateWeek = date.with(
                        TemporalAdjusters.previousOrSame(pattern.weekStart()));
                long weeks = ChronoUnit.WEEKS.between(anchorWeek, candidateWeek);
                yield weeks % rule.interval() == 0
                        && pattern.weekdays().contains(date.getDayOfWeek());
            }
            case MONTH_DAY -> monthMatches(rule, date)
                    && date.getDayOfMonth() == pattern.monthDay();
            case LAST_DAY_OF_MONTH -> monthMatches(rule, date)
                    && date.getDayOfMonth() == date.lengthOfMonth();
            case MONTHLY_NTH_WEEKDAY -> monthMatches(rule, date)
                    && date.equals(date.withDayOfMonth(1)
                            .with(TemporalAdjusters.dayOfWeekInMonth(
                                    pattern.ordinal(), pattern.weekday())));
            case MONTHLY_LAST_WEEKDAY -> monthMatches(rule, date)
                    && date.equals(date.with(TemporalAdjusters.lastInMonth(pattern.weekday())));
            case YEARLY -> {
                long years = date.getYear() - anchor.getYear();
                yield years % rule.interval() == 0
                        && date.getMonthValue() == pattern.yearDay().getMonthValue()
                        && date.getDayOfMonth() == pattern.yearDay().getDayOfMonth();
            }
        };
    }

    private static boolean monthMatches(CalendarRecurrenceRule rule, LocalDate date) {
        long months = ChronoUnit.MONTHS.between(
                YearMonth.from(rule.anchorDate()), YearMonth.from(date));
        return months % rule.interval() == 0;
    }

    private static CalendarOccurrence createOccurrence(
            CalendarRecurrenceRule rule, LocalDate date) {
        if (!rule.timed()) {
            var key = CalendarOccurrenceKey.allDay(date);
            return new CalendarOccurrence(
                    key, CalendarPlacement.allDay(date, date.plusDays(rule.daySpan())), false);
        }

        LocalDateTime localStart = LocalDateTime.of(date, rule.timedStart().toLocalTime());
        var offsets = rule.zoneId().getRules().getValidOffsets(localStart);
        if (offsets.isEmpty()) {
            return null;
        }
        var start = localStart.toInstant(offsets.getFirst());
        CalendarPlacement placement = rule.duration().isZero()
                ? CalendarPlacement.point(start, rule.zoneId())
                : CalendarPlacement.interval(start, start.plus(rule.duration()), rule.zoneId());
        return new CalendarOccurrence(CalendarOccurrenceKey.timed(localStart), placement, false);
    }

    private static void applyExceptions(
            CalendarRecurrenceRule rule,
            CalendarRecurrenceExceptions exceptions,
            CalendarRecurrenceWindow window,
            Map<CalendarOccurrenceKey, CalendarOccurrence> occurrences) {
        exceptions.overrides().forEach((key, placement) -> {
            requireCompatible(rule, key, placement);
            if (occurrences.containsKey(key) && !exceptions.exclusions().contains(key)) {
                occurrences.put(key, new CalendarOccurrence(key, placement, true));
            }
        });
        exceptions.additions().forEach((key, placement) -> {
            requireCompatible(rule, key, placement);
            if (window.contains(key.date()) && !exceptions.exclusions().contains(key)) {
                occurrences.put(key, new CalendarOccurrence(key, placement, true));
            }
        });
        exceptions.exclusions().forEach(occurrences::remove);
    }

    private static void requireCompatible(
            CalendarRecurrenceRule rule,
            CalendarOccurrenceKey key,
            CalendarPlacement placement) {
        if (rule.timed() != key.timed()) {
            throw new IllegalArgumentException("Exception key type must match recurrence type");
        }
        boolean timedPlacement = placement.kind() != CalendarPlacement.Kind.ALL_DAY;
        if (rule.timed() != timedPlacement) {
            throw new IllegalArgumentException("Exception placement type must match recurrence type");
        }
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }
}
