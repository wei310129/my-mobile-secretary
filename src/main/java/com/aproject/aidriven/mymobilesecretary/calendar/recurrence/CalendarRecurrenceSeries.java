package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CalendarRecurrenceSeries {

    private final UUID id;
    private final Map<Integer, CalendarRuleRevision> revisions = new LinkedHashMap<>();
    private final List<CalendarSeriesException> exceptions = new ArrayList<>();
    private final List<CalendarSeriesAudit> audit = new ArrayList<>();
    private final Map<String, Replay> replay = new LinkedHashMap<>();
    private int activeRevision;

    private CalendarRecurrenceSeries(UUID id, CalendarRecurrenceRule rule) {
        this.id = Objects.requireNonNull(id, "id");
        var firstKey = rule.timed()
                ? CalendarOccurrenceKey.timed(rule.timedStart())
                : CalendarOccurrenceKey.allDay(rule.allDayStart());
        revisions.put(
                1,
                new CalendarRuleRevision(
                        id,
                        1,
                        rule,
                        firstKey,
                        null,
                        null,
                        null,
                        CalendarRuleRevisionState.ACTIVE));
        activeRevision = 1;
    }

    public static CalendarRecurrenceSeries start(UUID id, CalendarRecurrenceRule rule) {
        return new CalendarRecurrenceSeries(id, rule);
    }

    public CalendarSeriesMutationResult overrideOccurrence(
            int expectedRevision,
            CalendarOccurrenceKey key,
            CalendarPlacement placement,
            String requestHash,
            String payloadHash) {
        return mutateOccurrence(
                expectedRevision,
                key,
                CalendarRecurrenceExceptionKind.OVERRIDDEN,
                placement,
                requestHash,
                payloadHash);
    }

    public CalendarSeriesMutationResult cancelOccurrence(
            int expectedRevision,
            CalendarOccurrenceKey key,
            String requestHash,
            String payloadHash) {
        return mutateOccurrence(
                expectedRevision,
                key,
                CalendarRecurrenceExceptionKind.EXCLUDED,
                null,
                requestHash,
                payloadHash);
    }

    private CalendarSeriesMutationResult mutateOccurrence(
            int expectedRevision,
            CalendarOccurrenceKey key,
            CalendarRecurrenceExceptionKind kind,
            CalendarPlacement placement,
            String requestHash,
            String payloadHash) {
        var replayed = replay(requestHash, payloadHash);
        if (replayed != null) {
            return replayed;
        }
        requireActiveRevision(expectedRevision);
        requireValidBoundary(revision(expectedRevision).rule(), key);
        var exception =
                new CalendarSeriesException(id, expectedRevision, key, kind, placement, requestHash);
        exceptions.add(exception);
        var result = new CalendarSeriesMutationResult(expectedRevision, expectedRevision);
        remember(requestHash, payloadHash, result);
        audit.add(new CalendarSeriesAudit(
                CalendarRecurrenceEditScope.THIS_OCCURRENCE,
                expectedRevision,
                expectedRevision,
                key,
                requestHash));
        return result;
    }

    public CalendarSeriesMutationResult splitThisAndFuture(
            int expectedRevision,
            CalendarOccurrenceKey boundary,
            CalendarRecurrenceRule nextRule,
            String requestHash,
            String payloadHash) {
        var replayed = replay(requestHash, payloadHash);
        if (replayed != null) {
            return replayed;
        }
        requireActiveRevision(expectedRevision);
        var previous = revision(expectedRevision);
        requireValidBoundary(previous.rule(), boundary);
        int successor = expectedRevision + 1;
        revisions.put(
                expectedRevision,
                previous.endAt(boundary, CalendarRuleRevisionState.SUPERSEDED));
        var nextStart = nextRule.timed()
                ? CalendarOccurrenceKey.timed(nextRule.timedStart())
                : CalendarOccurrenceKey.allDay(nextRule.allDayStart());
        revisions.put(
                successor,
                new CalendarRuleRevision(
                        id,
                        successor,
                        nextRule,
                        nextStart,
                        null,
                        expectedRevision,
                        boundary,
                        CalendarRuleRevisionState.ACTIVE));
        activeRevision = successor;
        var result = new CalendarSeriesMutationResult(expectedRevision, successor);
        remember(requestHash, payloadHash, result);
        audit.add(new CalendarSeriesAudit(
                CalendarRecurrenceEditScope.THIS_AND_FUTURE,
                expectedRevision,
                successor,
                boundary,
                requestHash));
        return result;
    }

    public CalendarSeriesMutationResult cancelEntireSeries(
            int expectedRevision,
            CalendarOccurrenceKey boundary,
            String requestHash,
            String payloadHash) {
        var replayed = replay(requestHash, payloadHash);
        if (replayed != null) {
            return replayed;
        }
        requireActiveRevision(expectedRevision);
        var current = revision(expectedRevision);
        requireValidBoundary(current.rule(), boundary);
        revisions.put(
                expectedRevision, current.endAt(boundary, CalendarRuleRevisionState.CANCELED));
        var result = new CalendarSeriesMutationResult(expectedRevision, expectedRevision);
        remember(requestHash, payloadHash, result);
        audit.add(new CalendarSeriesAudit(
                CalendarRecurrenceEditScope.ENTIRE_SERIES,
                expectedRevision,
                expectedRevision,
                boundary,
                requestHash));
        return result;
    }

    private static void requireValidBoundary(
            CalendarRecurrenceRule rule, CalendarOccurrenceKey boundary) {
        LocalDate date = boundary.date();
        boolean present = CalendarRecurrenceExpander.expand(
                        rule,
                        CalendarRecurrenceExceptions.none(),
                        CalendarRecurrenceWindow.between(date, date.plusDays(1), 10))
                .stream()
                .anyMatch(occurrence -> occurrence.key().equals(boundary));
        if (!present) {
            throw new IllegalArgumentException("Boundary must be a valid logical occurrence");
        }
    }

    private void requireActiveRevision(int expectedRevision) {
        if (expectedRevision != activeRevision) {
            throw new IllegalStateException("Stale recurrence revision");
        }
    }

    private CalendarSeriesMutationResult replay(String requestHash, String payloadHash) {
        var previous = replay.get(Objects.requireNonNull(requestHash, "requestHash"));
        if (previous == null) {
            return null;
        }
        if (!previous.payloadHash().equals(payloadHash)) {
            throw new IllegalStateException("Conflicting recurrence command replay");
        }
        return previous.result();
    }

    private void remember(
            String requestHash, String payloadHash, CalendarSeriesMutationResult result) {
        replay.put(
                requestHash,
                new Replay(Objects.requireNonNull(payloadHash, "payloadHash"), result));
    }

    public int activeRevision() {
        return activeRevision;
    }

    public CalendarRuleRevision revision(int revision) {
        var value = revisions.get(revision);
        if (value == null) {
            throw new IllegalArgumentException("Unknown recurrence revision");
        }
        return value;
    }

    public List<CalendarRuleRevision> revisions() {
        return List.copyOf(revisions.values());
    }

    public List<CalendarSeriesException> exceptions() {
        return List.copyOf(exceptions);
    }

    public List<CalendarSeriesAudit> audit() {
        return List.copyOf(audit);
    }

    private record Replay(String payloadHash, CalendarSeriesMutationResult result) {}

    public record CalendarSeriesMutationResult(int previousRevision, int successorRevision) {}

    public record CalendarSeriesException(
            UUID seriesId,
            int ruleRevision,
            CalendarOccurrenceKey key,
            CalendarRecurrenceExceptionKind kind,
            CalendarPlacement placement,
            String requestHash) {}

    public record CalendarSeriesAudit(
            CalendarRecurrenceEditScope scope,
            int previousRevision,
            int successorRevision,
            CalendarOccurrenceKey boundary,
            String requestHash) {}
}
