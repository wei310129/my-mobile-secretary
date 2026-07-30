package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CalendarRecurringReminderPlannerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    @Test
    void materializesThirtyDayWindowForEightTemplatesWithStableKeys() {
        UUID seriesId = UUID.randomUUID();
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 1, 9, 0),
                Duration.ofHours(1),
                ZONE,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.unbounded());
        List<CalendarRecurringReminderPlanner.Template> templates = IntStream.range(0, 8)
                .mapToObj(index -> CalendarRecurringReminderPlanner.Template.timed(
                        UUID.nameUUIDFromBytes(("template-" + index).getBytes()),
                        1,
                        Duration.ofMinutes(-index * 5L),
                        ZONE))
                .toList();
        var window = CalendarRecurrenceWindow.between(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 30);

        var first = CalendarRecurringReminderPlanner.plan(
                seriesId,
                3,
                rule,
                CalendarRecurrenceExceptions.none(),
                window,
                templates);
        var replay = CalendarRecurringReminderPlanner.plan(
                seriesId,
                3,
                rule,
                CalendarRecurrenceExceptions.none(),
                window,
                templates);

        assertThat(first).hasSize(240).isEqualTo(replay);
        assertThat(first)
                .extracting(CalendarRecurringReminderPlanner.Materialization::key)
                .doesNotHaveDuplicates();
        assertThat(first)
                .extracting(value -> value.key().recurrenceRuleRevision())
                .containsOnly(3);
    }

    @Test
    void excludedOccurrenceCancelsOnlyItsEightFutureMaterializations() {
        UUID seriesId = UUID.randomUUID();
        var rule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 1, 9, 0),
                Duration.ZERO,
                ZONE,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.count(3));
        var excluded = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 2, 9, 0));
        var templates = IntStream.range(0, 8)
                .mapToObj(index -> CalendarRecurringReminderPlanner.Template.timed(
                        UUID.nameUUIDFromBytes(("skip-" + index).getBytes()),
                        1,
                        Duration.ZERO,
                        ZONE))
                .toList();

        var planned = CalendarRecurringReminderPlanner.plan(
                seriesId,
                1,
                rule,
                CalendarRecurrenceExceptions.none().exclude(excluded),
                CalendarRecurrenceWindow.between(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), 10),
                templates);

        assertThat(planned).hasSize(16);
        assertThat(planned)
                .extracting(value -> value.key().occurrenceKey())
                .doesNotContain(excluded);
    }

    @Test
    void quotaCountsTemplatesAndRejectsTheNinthBeforeExpansion() {
        var rule = CalendarRecurrenceRule.allDay(
                LocalDate.of(2026, 8, 1),
                1,
                CalendarRecurrencePattern.daily(),
                1,
                CalendarRecurrenceEnd.unbounded());
        var templates = IntStream.range(0, 9)
                .mapToObj(index -> CalendarRecurringReminderPlanner.Template.timed(
                        UUID.randomUUID(), 1, Duration.ZERO, ZONE))
                .toList();

        assertThatThrownBy(() -> CalendarRecurringReminderPlanner.plan(
                        UUID.randomUUID(),
                        1,
                        rule,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(
                                LocalDate.of(2026, 8, 1),
                                LocalDate.of(2027, 8, 1),
                                365),
                        templates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 8");
    }
}
