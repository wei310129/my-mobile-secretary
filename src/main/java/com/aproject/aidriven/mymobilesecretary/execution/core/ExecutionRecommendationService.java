package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ExecutionRecommendationService {

    private static final Duration DEFAULT_PREP_BUFFER = Duration.ofMinutes(15);
    private static final Comparator<Candidate> ORDER =
            Comparator.comparingInt((Candidate candidate) -> candidate.urgent() ? 0 : 1)
                    .thenComparingInt(Candidate::urgencyClass)
                    .thenComparing(
                            Candidate::effectiveAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(Candidate::priorityRank)
                    .thenComparing(candidate -> candidate.item().kind())
                    .thenComparing(candidate -> candidate.item().stableId());

    private final Clock clock;
    private final ExecutionAgendaSource source;

    public ExecutionRecommendationService(Clock clock, ExecutionAgendaSource source) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
    }

    public ExecutionQueue recommend() {
        var snapshot = Objects.requireNonNull(source.load(), "source snapshot");
        validateUniqueSourceIdentities(snapshot);
        var now = clock.instant();
        var candidates = candidates(snapshot, now).stream().sorted(ORDER).toList();

        Optional<Candidate> selectedNow =
                candidates.stream().filter(Candidate::urgent).findFirst();
        boolean fallback = selectedNow.isEmpty();
        if (fallback) {
            selectedNow =
                    candidates.stream()
                            .filter(candidate -> candidate.item().kind() == ExecutionItemKind.TASK)
                            .findFirst();
        }

        var nowItem =
                selectedNow.map(Candidate::item)
                        .map(item -> fallback ? item.asNotUrgent() : item);
        var selectedIdentity =
                selectedNow.map(candidate -> candidate.item().stableId()).orElse(null);
        var remaining =
                candidates.stream()
                        .filter(
                                candidate ->
                                        !candidate.item().stableId().equals(selectedIdentity))
                        .toList();
        var next = remaining.stream().findFirst().map(Candidate::item);
        var later =
                remaining.stream()
                        .skip(next.isPresent() ? 1 : 0)
                        .map(Candidate::item)
                        .toList();
        return new ExecutionQueue(
                nowItem, next, new ExecutionLaterQueue(later));
    }

    private static List<Candidate> candidates(
            ExecutionAgendaSnapshot snapshot, Instant now) {
        var candidates = new ArrayList<Candidate>();
        snapshot.tasks().stream()
                .filter(ExecutionTaskItem::open)
                .filter(task -> !task.dependencyBlocked())
                .map(task -> taskCandidate(task, now))
                .forEach(candidates::add);
        snapshot.calendarItems().stream()
                .filter(ExecutionCalendarItem::actorAdopted)
                .filter(item -> item.endsAt().isAfter(now))
                .map(item -> calendarCandidate(item, now))
                .forEach(candidates::add);
        return candidates;
    }

    private static Candidate taskCandidate(
            ExecutionTaskItem task, Instant now) {
        boolean overdue = task.dueAt() != null && !task.dueAt().isAfter(now);
        return new Candidate(
                new ExecutionRecommendationItem(
                        ExecutionItemKind.TASK,
                        task.stableId(),
                        task.title(),
                        task.dueAt(),
                        false),
                overdue,
                overdue ? 1 : 2,
                task.dueAt(),
                priorityRank(task.priority()));
    }

    private static Candidate calendarCandidate(
            ExecutionCalendarItem item, Instant now) {
        var prepStartsAt =
                item.explicitPrepStartsAt() == null
                        ? item.startsAt()
                                .minus(
                                        item.prepBuffer() == null
                                                ? DEFAULT_PREP_BUFFER
                                                : item.prepBuffer())
                        : item.explicitPrepStartsAt();
        boolean inPrepOrActiveWindow =
                item.fixed()
                        && !item.allDay()
                        && !now.isBefore(prepStartsAt)
                        && now.isBefore(item.endsAt());
        return new Candidate(
                new ExecutionRecommendationItem(
                        ExecutionItemKind.CALENDAR,
                        item.stableId(),
                        item.title(),
                        item.startsAt(),
                        false),
                inPrepOrActiveWindow,
                inPrepOrActiveWindow ? 0 : 2,
                item.startsAt(),
                0);
    }

    private static int priorityRank(ExecutionPriority priority) {
        return switch (priority) {
            case HIGH -> 0;
            case NORMAL -> 1;
            case LOW -> 2;
        };
    }

    private static void validateUniqueSourceIdentities(
            ExecutionAgendaSnapshot snapshot) {
        var identities = new HashSet<String>();
        snapshot.tasks().stream()
                .map(ExecutionTaskItem::stableId)
                .forEach(identity -> addSourceIdentity(identities, identity));
        snapshot.calendarItems().stream()
                .map(ExecutionCalendarItem::stableId)
                .forEach(identity -> addSourceIdentity(identities, identity));
    }

    private static void addSourceIdentity(
            HashSet<String> identities, String identity) {
        if (!identities.add(identity)) {
            throw new IllegalArgumentException(
                    "stable identity must be unique across source items");
        }
    }

    private record Candidate(
            ExecutionRecommendationItem item,
            boolean urgent,
            int urgencyClass,
            Instant effectiveAt,
            int priorityRank) {}
}
