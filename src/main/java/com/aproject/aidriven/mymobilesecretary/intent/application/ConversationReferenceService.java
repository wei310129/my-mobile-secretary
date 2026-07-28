package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Captures typed ids rendered in a reply so a future LINE quote resolves without an LLM. */
@Service
public class ConversationReferenceService {

    public enum Kind { SCHEDULE, TASK, PLACE, KNOWLEDGE, DRAFT }

    private final ConversationContextService context;
    private final ScheduleService schedules;
    private final TaskService tasks;

    public ConversationReferenceService(
            ConversationContextService context, ScheduleService schedules, TaskService tasks) {
        this.context = context;
        this.schedules = schedules;
        this.tasks = tasks;
    }

    /** Stable wire format: KIND:id:displayOrdinal. */
    public String capture(IntentResult result) {
        try {
            return captureResolved(result);
        } catch (RuntimeException ignored) {
            // Reference metadata is auxiliary; it must never turn a successful reply into a failure.
            return null;
        }
    }

    private String captureResolved(IntentResult result) {
        if (result == null || result.message() == null || result.message().isBlank()) return null;
        ConversationSnapshot snapshot = context.snapshot();
        List<Candidate> candidates = new ArrayList<>();
        for (Long id : snapshot.lastScheduleListIds()) {
            var item = schedules.getSchedule(id);
            addIfRendered(candidates, Kind.SCHEDULE, id, item.getTitle(), result.message());
        }
        if (snapshot.lastScheduleId() != null
                && snapshot.lastScheduleListIds().stream()
                        .noneMatch(snapshot.lastScheduleId()::equals)) {
            var item = schedules.getSchedule(snapshot.lastScheduleId());
            addIfRendered(candidates, Kind.SCHEDULE, item.getId(), item.getTitle(), result.message());
        }
        for (Long id : snapshot.lastTaskListIds()) {
            var item = tasks.getTask(id);
            addIfRendered(candidates, Kind.TASK, id, item.getTitle(), result.message());
        }
        if (snapshot.lastTaskId() != null
                && snapshot.lastTaskListIds().stream().noneMatch(snapshot.lastTaskId()::equals)) {
            var item = tasks.getTask(snapshot.lastTaskId());
            addIfRendered(candidates, Kind.TASK, item.getId(), item.getTitle(), result.message());
        }
        ReferenceScope scope = ReferenceScope.forAction(result.action());
        List<Candidate> rendered = candidates.stream()
                .filter(candidate -> scope.accepts(candidate, candidates))
                .sorted(Comparator.comparingInt(Candidate::displayIndex).thenComparing(Candidate::id))
                .distinct().toList();
        if (rendered.isEmpty()) return null;
        StringBuilder payload = new StringBuilder();
        for (int index = 0; index < rendered.size(); index++) {
            if (index > 0) payload.append(';');
            Candidate candidate = rendered.get(index);
            payload.append(candidate.kind()).append(':').append(candidate.id())
                    .append(':').append(index + 1);
        }
        return payload.toString();
    }

    private static void addIfRendered(
            List<Candidate> candidates, Kind kind, Long id, String title, String message) {
        if (title == null || title.isBlank()) return;
        int index = message.indexOf(title);
        if (index >= 0) candidates.add(new Candidate(kind, id, index, title));
    }

    private record Candidate(Kind kind, Long id, int displayIndex, String title) { }

    private record ReferenceScope(Kind preferredKind) {

        private static ReferenceScope forAction(IntentResult.Action action) {
            String name = action == null ? "" : action.name();
            boolean scheduleAction = name.contains("SCHEDULE");
            boolean taskAction = name.contains("TASK");
            if (scheduleAction != taskAction) {
                return new ReferenceScope(scheduleAction ? Kind.SCHEDULE : Kind.TASK);
            }
            return new ReferenceScope(null);
        }

        private boolean accepts(Candidate candidate, List<Candidate> candidates) {
            if (preferredKind == null || candidate.kind() == preferredKind) return true;
            return candidates.stream().noneMatch(other -> other.kind() == preferredKind
                    && other.displayIndex() == candidate.displayIndex()
                    && other.title().equals(candidate.title()));
        }
    }
}
