package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRecurrenceSeriesTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final CalendarRecurrenceRule ORIGINAL_RULE = CalendarRecurrenceRule.timed(
            LocalDateTime.of(2026, 7, 1, 19, 0),
            Duration.ofHours(1),
            ZONE,
            CalendarRecurrencePattern.weekly(
                    java.util.Set.of(java.time.DayOfWeek.WEDNESDAY),
                    java.time.DayOfWeek.MONDAY),
            1,
            CalendarRecurrenceEnd.unbounded());

    @Test
    void thisOccurrenceCreatesOneRevisionBoundOverrideAndReplayIsIdempotent() {
        var series = CalendarRecurrenceSeries.start(UUID.randomUUID(), ORIGINAL_RULE);
        var key = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 7, 8, 19, 0));
        var replacement = CalendarPlacement.interval(
                Instant.parse("2026-07-08T12:00:00Z"),
                Instant.parse("2026-07-08T13:00:00Z"),
                ZONE);

        var first = series.overrideOccurrence(1, key, replacement, "request-1", "payload-1");
        var replay = series.overrideOccurrence(1, key, replacement, "request-1", "payload-1");

        assertThat(first).isEqualTo(replay);
        assertThat(series.exceptions()).singleElement().satisfies(exception -> {
            assertThat(exception.ruleRevision()).isEqualTo(1);
            assertThat(exception.key()).isEqualTo(key);
            assertThat(exception.kind()).isEqualTo(CalendarRecurrenceExceptionKind.OVERRIDDEN);
        });
        assertThat(series.activeRevision()).isEqualTo(1);
    }

    @Test
    void thisAndFutureSplitsAtAValidBoundaryAndPreservesPastRevision() {
        var series = CalendarRecurrenceSeries.start(UUID.randomUUID(), ORIGINAL_RULE);
        var boundary = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 5, 19, 0));
        var nextRule = CalendarRecurrenceRule.timed(
                LocalDateTime.of(2026, 8, 5, 20, 0),
                Duration.ofHours(1),
                ZONE,
                CalendarRecurrencePattern.weekly(
                        java.util.Set.of(java.time.DayOfWeek.WEDNESDAY),
                        java.time.DayOfWeek.MONDAY),
                1,
                CalendarRecurrenceEnd.unbounded());

        var split = series.splitThisAndFuture(
                1, boundary, nextRule, "request-2", "payload-2");

        assertThat(split.previousRevision()).isEqualTo(1);
        assertThat(split.successorRevision()).isEqualTo(2);
        assertThat(series.revision(1).effectiveUntilExclusive()).isEqualTo(boundary);
        assertThat(series.revision(1).rule()).isEqualTo(ORIGINAL_RULE);
        assertThat(series.revision(2).splitFromKey()).isEqualTo(boundary);
        assertThat(series.activeRevision()).isEqualTo(2);
    }

    @Test
    void entireSeriesCancellationOnlyTerminatesFutureProjection() {
        var series = CalendarRecurrenceSeries.start(UUID.randomUUID(), ORIGINAL_RULE);
        var boundary = CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 5, 19, 0));

        series.cancelEntireSeries(1, boundary, "request-3", "payload-3");

        assertThat(series.revision(1).effectiveUntilExclusive()).isEqualTo(boundary);
        assertThat(series.revision(1).state()).isEqualTo(CalendarRuleRevisionState.CANCELED);
        assertThat(series.audit()).singleElement().satisfies(entry -> {
            assertThat(entry.scope()).isEqualTo(CalendarRecurrenceEditScope.ENTIRE_SERIES);
            assertThat(entry.boundary()).isEqualTo(boundary);
        });
    }

    @Test
    void rejectsInvalidBoundaryStaleRevisionAndConflictingReplayWithoutMutation() {
        var series = CalendarRecurrenceSeries.start(UUID.randomUUID(), ORIGINAL_RULE);
        var invalidBoundary =
                CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 6, 19, 0));
        int revisionCount = series.revisions().size();

        assertThatThrownBy(() -> series.cancelEntireSeries(
                        1, invalidBoundary, "request-4", "payload-4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> series.overrideOccurrence(
                        2,
                        CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 5, 19, 0)),
                        CalendarPlacement.point(Instant.parse("2026-08-05T11:00:00Z"), ZONE),
                        "request-5",
                        "payload-5"))
                .isInstanceOf(IllegalStateException.class);
        series.cancelOccurrence(
                1,
                CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 5, 19, 0)),
                "request-6",
                "payload-6");
        assertThatThrownBy(() -> series.cancelOccurrence(
                        1,
                        CalendarOccurrenceKey.timed(LocalDateTime.of(2026, 8, 12, 19, 0)),
                        "request-6",
                        "different-payload"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(series.revisions()).hasSize(revisionCount);
        assertThat(series.exceptions()).hasSize(1);
    }
}
