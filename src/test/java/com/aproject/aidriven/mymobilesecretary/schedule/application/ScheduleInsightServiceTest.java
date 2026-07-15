package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleInsightServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T00:00:00Z");

    @Mock
    private ScheduleService scheduleService;

    private ScheduleInsightService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleInsightService(scheduleService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void nextIgnoresFinishedItemsAndUsesConfirmedOrder() {
        ScheduleItem finished = schedule("已結束", NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        ScheduleItem next = schedule("下一個", NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        when(scheduleService.listSchedules(ScheduleStatus.CONFIRMED)).thenReturn(List.of(finished, next));

        assertThat(service.next()).contains(next);
    }

    @Test
    void gapReportsFreeMinutesAndOverlap() {
        ScheduleItem first = schedule("A", NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        ScheduleItem later = schedule("B", NOW.plusSeconds(9000), NOW.plusSeconds(10800));
        ScheduleItem overlap = schedule("C", NOW.plusSeconds(5400), NOW.plusSeconds(8000));

        assertThat(service.gap(first, later).duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(service.gap(first, later).overlapping()).isFalse();
        assertThat(service.gap(first, overlap).overlapping()).isTrue();
    }

    @Test
    void groupsByTaipeiCalendarDay() {
        ScheduleItem late = schedule("晚間", Instant.parse("2030-08-01T15:30:00Z"),
                Instant.parse("2030-08-01T16:00:00Z")); // 台北 8/1 23:30
        ScheduleItem afterMidnight = schedule("凌晨", Instant.parse("2030-08-01T16:30:00Z"),
                Instant.parse("2030-08-01T17:00:00Z")); // 台北 8/2 00:30

        assertThat(service.groupByDay(List.of(late, afterMidnight)).keySet())
                .containsExactly(LocalDate.of(2030, 8, 1), LocalDate.of(2030, 8, 2));
    }

    @Test
    void conflictsReturnsOnlyOverlappingPairs() {
        ScheduleItem first = schedule("A", NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        ScheduleItem overlap = schedule("B", NOW.plusSeconds(5400), NOW.plusSeconds(8000));
        ScheduleItem later = schedule("C", NOW.plusSeconds(9000), NOW.plusSeconds(10800));

        assertThat(service.conflicts(List.of(first, overlap, later)))
                .singleElement().satisfies(gap -> {
                    assertThat(gap.first()).isSameAs(first);
                    assertThat(gap.second()).isSameAs(overlap);
                });
    }

    private static ScheduleItem schedule(String title, Instant start, Instant end) {
        ScheduleItem item = ScheduleItem.propose(title, start, end, null, NOW.minusSeconds(60));
        item.confirm(NOW.minusSeconds(30));
        return item;
    }
}
