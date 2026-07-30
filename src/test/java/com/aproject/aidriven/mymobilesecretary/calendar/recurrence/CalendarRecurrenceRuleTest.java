package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CalendarRecurrenceRuleTest {

    @Test
    void countsDtstartAndExpandsEveryTwoDaysExactlyFiveTimes() {
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 1, 9, 0),
                Duration.ofHours(1),
                ZoneId.of("Asia/Taipei"),
                CalendarRecurrencePattern.daily(),
                2,
                CalendarRecurrenceEnd.count(5));

        var occurrences = CalendarRecurrenceExpander.expand(
                rule,
                CalendarRecurrenceExceptions.none(),
                CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20), 100));

        assertThat(occurrences)
                .extracting(occurrence -> occurrence.key().timedStart())
                .containsExactly(
                        LocalDateTime.of(2026, 8, 1, 9, 0),
                        LocalDateTime.of(2026, 8, 3, 9, 0),
                        LocalDateTime.of(2026, 8, 5, 9, 0),
                        LocalDateTime.of(2026, 8, 7, 9, 0),
                        LocalDateTime.of(2026, 8, 9, 9, 0));
    }

    @Test
    void expandsMultiWeekdayEveryThreeWeeksWithoutDuplicates() {
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 3, 19, 0),
                Duration.ofHours(1),
                ZoneId.of("Asia/Taipei"),
                CalendarRecurrencePattern.weekly(
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                        DayOfWeek.MONDAY),
                3,
                CalendarRecurrenceEnd.count(6));

        var occurrences = CalendarRecurrenceExpander.expand(
                rule,
                CalendarRecurrenceExceptions.none(),
                CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), 100));

        assertThat(occurrences)
                .extracting(occurrence -> occurrence.key().timedStart().toLocalDate())
                .containsExactly(
                        LocalDate.of(2026, 8, 3),
                        LocalDate.of(2026, 8, 5),
                        LocalDate.of(2026, 8, 7),
                        LocalDate.of(2026, 8, 24),
                        LocalDate.of(2026, 8, 26),
                        LocalDate.of(2026, 8, 28));
    }

    @Test
    void skipsInvalidMonthDaysWithoutConsumingCount() {
        var rule = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 1, 31),
                1,
                CalendarRecurrencePattern.monthDay(31),
                1,
                CalendarRecurrenceEnd.count(4));

        var occurrences = CalendarRecurrenceExpander.expand(
                rule,
                CalendarRecurrenceExceptions.none(),
                CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1), 100));

        assertThat(occurrences)
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 5, 31),
                        LocalDate.of(2026, 7, 31));
    }

    @Test
    void supportsLastDayAndLeapDayWithoutClamping() {
        var lastDay = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 1, 31),
                1,
                CalendarRecurrencePattern.lastDayOfMonth(),
                1,
                CalendarRecurrenceEnd.count(4));
        var leapDay = CalendarRecurrenceRule.allDay(
                LocalDate.of(2024, 2, 29),
                1,
                CalendarRecurrencePattern.yearly(MonthDay.of(2, 29)),
                1,
                CalendarRecurrenceEnd.count(3));

        assertThat(CalendarRecurrenceExpander.expand(
                        lastDay,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), 100)))
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 2, 28),
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 4, 30));
        assertThat(CalendarRecurrenceExpander.expand(
                        leapDay,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2024, 1, 1), LocalDate.of(2033, 1, 1), 100)))
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2024, 2, 29),
                        LocalDate.of(2028, 2, 29),
                        LocalDate.of(2032, 2, 29));
    }

    @Test
    void includesTheOccurrenceExactlyAtUntilBoundary() {
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 1, 9, 0),
                Duration.ZERO,
                ZoneId.of("Asia/Taipei"),
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.untilTimed(
                        LocalDateTime.of(2026, 8, 3, 9, 0)));

        assertThat(CalendarRecurrenceExpander.expand(
                        rule,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), 100)))
                .extracting(occurrence -> occurrence.key().timedStart())
                .containsExactly(
                        LocalDateTime.of(2026, 8, 1, 9, 0),
                        LocalDateTime.of(2026, 8, 2, 9, 0),
                        LocalDateTime.of(2026, 8, 3, 9, 0));
    }

    @Test
    void appliesOverrideAdditionAndExclusionWithExclusionPrecedence() {
        var zone = ZoneId.of("Asia/Taipei");
        var second = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 2, 9, 0));
        var third = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 3, 9, 0));
        var added = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 10, 9, 0));
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 1, 9, 0),
                Duration.ofHours(1),
                zone,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.count(3));
        var exceptions = CalendarRecurrenceExceptions.none()
                .override(
                        second,
                        CalendarPlacement.interval(
                                Instant.parse("2026-08-02T03:00:00Z"),
                                Instant.parse("2026-08-02T04:00:00Z"),
                                zone))
                .exclude(third)
                .add(
                        third,
                        CalendarPlacement.point(
                                Instant.parse("2026-08-03T01:00:00Z"), zone))
                .add(
                        added,
                        CalendarPlacement.point(
                                Instant.parse("2026-08-10T01:00:00Z"), zone));

        var occurrences = CalendarRecurrenceExpander.expand(
                rule,
                exceptions,
                CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20), 100));

        assertThat(occurrences)
                .extracting(CalendarOccurrence::key)
                .containsExactly(
                        CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 1, 9, 0)),
                        second,
                        added);
        assertThat(occurrences.get(1).overridden()).isTrue();
        assertThat(occurrences.get(1).placement())
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-08-02T03:00:00Z"),
                        Instant.parse("2026-08-02T04:00:00Z"),
                        zone));
    }

    @Test
    void skipsDstGapWithoutConsumingCountAndUsesFirstOverlapOffset() {
        var zone = ZoneId.of("America/New_York");
        var gapRule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 3, 7, 2, 30),
                Duration.ZERO,
                zone,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.count(2));
        var overlapRule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 11, 1, 1, 30),
                Duration.ZERO,
                zone,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.count(1));

        assertThat(CalendarRecurrenceExpander.expand(
                        gapRule,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 12), 100)))
                .extracting(occurrence -> occurrence.key().timedStart())
                .containsExactly(
                        LocalDateTime.of(2026, 3, 7, 2, 30),
                        LocalDateTime.of(2026, 3, 9, 2, 30));
        assertThat(CalendarRecurrenceExpander.expand(
                                overlapRule,
                                CalendarRecurrenceExceptions.none(),
                                CalendarRecurrenceWindow.between(
                                        LocalDate.of(2026, 11, 1),
                                        LocalDate.of(2026, 11, 2),
                                        100))
                        .getFirst()
                        .placement())
                .isEqualTo(CalendarPlacement.point(
                        Instant.parse("2026-11-01T05:30:00Z"), zone));
    }

    @Test
    void supportsNthAndLastWeekdayWithoutClamping() {
        var secondTuesday = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 1, 13),
                1,
                CalendarRecurrencePattern.monthlyNthWeekday(2, DayOfWeek.TUESDAY),
                1,
                CalendarRecurrenceEnd.count(3));
        var lastMonday = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 1, 26),
                1,
                CalendarRecurrencePattern.monthlyLastWeekday(DayOfWeek.MONDAY),
                1,
                CalendarRecurrenceEnd.count(3));

        assertThat(CalendarRecurrenceExpander.expand(
                        secondTuesday,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1), 100)))
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2026, 1, 13),
                        LocalDate.of(2026, 2, 10),
                        LocalDate.of(2026, 3, 10));
        assertThat(CalendarRecurrenceExpander.expand(
                        lastMonday,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1), 100)))
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2026, 1, 26),
                        LocalDate.of(2026, 2, 23),
                        LocalDate.of(2026, 3, 30));
    }

    @Test
    void includesAllDayUntilBoundaryAndAppliesCallerLimit() {
        var rule = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 1, 1),
                1,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.untilDate(LocalDate.of(2026, 1, 10)));

        assertThat(CalendarRecurrenceExpander.expand(
                        rule,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 4)))
                .extracting(occurrence -> occurrence.key().allDayStart())
                .containsExactly(
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 2),
                        LocalDate.of(2026, 1, 3),
                        LocalDate.of(2026, 1, 4));
    }

    @Test
    void requiresFiniteOrderedWindowAndPositiveCallerLimit() {
        assertThatThrownBy(() -> CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
