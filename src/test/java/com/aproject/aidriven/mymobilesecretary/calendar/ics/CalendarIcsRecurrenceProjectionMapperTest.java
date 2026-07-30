package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrencePattern;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarIcsRecurrenceProjectionMapperTest {

    @Test
    void mapsIsoWeekdaysAndExplicitLastSentinels() {
        var weekly = CalendarIcsRecurrenceProjectionMapper.pattern(
                "WEEKLY", List.of(1, 3, 5), 1, null, null, null, null, null);
        var lastDay = CalendarIcsRecurrenceProjectionMapper.pattern(
                "MONTHLY", List.of(), null, -1, null, null, null, null);
        var lastTuesday = CalendarIcsRecurrenceProjectionMapper.pattern(
                "MONTHLY", List.of(), null, null, -1, 2, null, null);

        assertThat(weekly.kind())
                .isEqualTo(CalendarRecurrencePattern.Kind.WEEKLY);
        assertThat(weekly.weekdays())
                .containsExactlyInAnyOrder(
                        DayOfWeek.MONDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.FRIDAY);
        assertThat(lastDay.kind())
                .isEqualTo(
                        CalendarRecurrencePattern.Kind.LAST_DAY_OF_MONTH);
        assertThat(lastTuesday.kind())
                .isEqualTo(
                        CalendarRecurrencePattern.Kind.MONTHLY_LAST_WEEKDAY);
        assertThat(lastTuesday.weekday()).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    void malformedPersistenceShapesFailClosed() {
        assertThatThrownBy(() -> CalendarIcsRecurrenceProjectionMapper.pattern(
                        "WEEKLY",
                        List.of(0, 8),
                        1,
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarIcsRecurrenceProjectionMapper.end(
                        "UNTIL_DATE",
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CalendarIcsRecurrenceProjectionMapper.end(
                                "COUNT", 3, null, null)
                        .count())
                .isEqualTo(3);
        assertThat(CalendarIcsRecurrenceProjectionMapper.end(
                                "UNTIL_DATE",
                                null,
                                LocalDate.of(2026, 8, 31),
                                null)
                        .untilDate())
                .isEqualTo(LocalDate.of(2026, 8, 31));
    }
}
