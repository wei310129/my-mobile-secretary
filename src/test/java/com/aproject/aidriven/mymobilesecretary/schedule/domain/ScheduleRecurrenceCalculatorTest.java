package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScheduleRecurrenceCalculatorTest {

    @Test
    void firstMondayAdvancesToFirstMondayOfNextMonth() {
        LocalDate next = ScheduleRecurrenceCalculator.nextDate(
                LocalDate.of(2026, 8, 3), ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY);

        assertThat(next).isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    void missingFifthWeekdaySkipsMonthsWithoutOne() {
        LocalDate next = ScheduleRecurrenceCalculator.nextDate(
                LocalDate.of(2026, 8, 31), ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY);

        assertThat(next).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void projectionMatchesOnlyTheSameOrdinalAndWeekday() {
        LocalDate anchor = LocalDate.of(2026, 8, 3);

        assertThat(ScheduleRecurrenceCalculator.occursOn(anchor,
                LocalDate.of(2026, 9, 7), ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY)).isTrue();
        assertThat(ScheduleRecurrenceCalculator.occursOn(anchor,
                LocalDate.of(2026, 9, 14), ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY)).isFalse();
    }
}
