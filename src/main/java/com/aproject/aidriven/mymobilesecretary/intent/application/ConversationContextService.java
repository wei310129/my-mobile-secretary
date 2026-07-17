package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.ConversationContextRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 「那個、第二個、跟上一個一樣」的單人短期上下文。 */
@Service
@Transactional
public class ConversationContextService {

    private final ConversationContextRepository repository;
    private final Clock clock;

    public ConversationContextService(ConversationContextRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConversationSnapshot snapshot() {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        return findCurrent(scope)
                .map(c -> new ConversationSnapshot(c.getLastTaskId(), c.getLastScheduleId(),
                        c.getLastPlaceId(), parseIds(c.getLastTaskListIds()),
                        parseIds(c.getLastScheduleListIds()), c.getLastAction(),
                        c.getLastUserText(), c.getLastAssistantText()))
                .orElseGet(ConversationSnapshot::empty);
    }

    public void rememberExchange(String userText, IntentResult result) {
        ConversationContext context = current();
        Instant now = Instant.now(clock);
        context.rememberExchange(result.action().name(), userText, result.message(), now);
        if (result.task() != null) {
            context.rememberTask(result.task().getId(), now);
        }
        if (result.decision() != null && result.decision().item() != null) {
            context.rememberSchedule(result.decision().item().getId(), now);
        }
    }

    public void rememberTask(Task task) {
        current().rememberTask(task.getId(), Instant.now(clock));
    }

    public void rememberSchedule(ScheduleItem item) {
        current().rememberSchedule(item.getId(), Instant.now(clock));
    }

    public void rememberPlace(Long placeId) {
        current().rememberPlace(placeId, Instant.now(clock));
    }

    public void rememberTaskList(List<Task> tasks) {
        current().rememberTaskList(joinIds(tasks.stream().map(Task::getId).toList()), Instant.now(clock));
    }

    public void rememberScheduleList(List<ScheduleItem> items) {
        current().rememberScheduleList(joinIds(items.stream().map(ScheduleItem::getId).toList()), Instant.now(clock));
    }

    public Long taskIdAt(Integer oneBasedOrdinal) {
        ConversationSnapshot snapshot = snapshot();
        if (oneBasedOrdinal == null) {
            return snapshot.lastTaskId();
        }
        return at(snapshot.lastTaskListIds(), oneBasedOrdinal);
    }

    public Long scheduleIdAt(Integer oneBasedOrdinal) {
        ConversationSnapshot snapshot = snapshot();
        if (oneBasedOrdinal == null) {
            return snapshot.lastScheduleId();
        }
        return at(snapshot.lastScheduleListIds(), oneBasedOrdinal);
    }

    private ConversationContext current() {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        return findCurrent(scope).orElseGet(() -> repository.save(
                ConversationContext.create(scope.channel(), Instant.now(clock))));
    }

    private java.util.Optional<ConversationContext> findCurrent(WorkspaceContext scope) {
        return repository.findByWorkspaceIdAndCreatedByUserIdAndChannel(
                scope.workspaceId(), scope.actorId(), scope.channel());
    }

    private static Long at(List<Long> ids, int ordinal) {
        return ordinal >= 1 && ordinal <= ids.size() ? ids.get(ordinal - 1) : null;
    }

    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
    }
}
