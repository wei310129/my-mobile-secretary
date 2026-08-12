package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.ConversationContextRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 「那個、第二個、跟上一個一樣」的單人短期上下文。 */
@Service
@Transactional
public class ConversationContextService {

    private final ConversationContextRepository repository;
    private final Clock clock;
    private final ConversationScopeResolver scopeResolver;
    private final ConversationFocusRepository focusRepository;
    private final ThreadLocal<ExchangeTouches> exchangeTouches = new ThreadLocal<>();

    public ConversationContextService(ConversationContextRepository repository, Clock clock,
                                      ConversationScopeResolver scopeResolver) {
        this(repository, clock, scopeResolver, null);
    }

    @Autowired
    public ConversationContextService(ConversationContextRepository repository, Clock clock,
                                      ConversationScopeResolver scopeResolver,
                                      ConversationFocusRepository focusRepository) {
        this.repository = repository;
        this.clock = clock;
        this.scopeResolver = scopeResolver;
        this.focusRepository = focusRepository;
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

    /**
     * Starts one inbound turn. List retention is request-local rather than entity-transient because
     * handlers and the final exchange recorder may run in separate Spring transactions.
     */
    public void beginExchange() {
        exchangeTouches.set(new ExchangeTouches());
    }

    public void abandonExchange() {
        exchangeTouches.remove();
    }

    /** Keeps bounded referents across feedback, meta, and other non-consuming interjections. */
    public void preserveReferencesForInterjection() {
        ExchangeTouches current = touches();
        current.taskList = true;
        current.scheduleList = true;
        current.objectAnnotationState = true;
    }

    public void rememberExchange(String userText, IntentResult result) {
        ExchangeTouches touches = exchangeTouches.get();
        try {
            ConversationContext context = current();
            Instant now = Instant.now(clock);
            context.rememberExchange(result.action().name(), userText, result.message(), now,
                    touches != null && touches.taskList,
                    touches != null && touches.scheduleList,
                    touches != null && touches.objectAnnotationState);
            if (result.task() != null) {
                context.rememberTask(result.task().getId(), now);
            }
            if (result.decision() != null && result.decision().item() != null) {
                context.rememberSchedule(result.decision().item().getId(), now);
            }
        } finally {
            exchangeTouches.remove();
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
        touches().taskList = true;
        current().rememberTaskList(joinIds(tasks.stream().map(Task::getId).toList()), Instant.now(clock));
    }

    public void rememberScheduleList(List<ScheduleItem> items) {
        touches().scheduleList = true;
        current().rememberScheduleList(joinIds(items.stream().map(ScheduleItem::getId).toList()), Instant.now(clock));
    }

    public void rememberObjectAnnotationList(List<Long> ids) {
        touches().objectAnnotationState = true;
        current().rememberObjectAnnotationList(joinIds(ids), Instant.now(clock));
    }

    public Long objectAnnotationIdAt(Integer oneBasedOrdinal) {
        if (oneBasedOrdinal == null) return null;
        return at(parseIds(current().getLastObjectAnnotationListIds()), oneBasedOrdinal);
    }

    public void prepareObjectAnnotationDelete(Long annotationId) {
        touches().objectAnnotationState = true;
        current().prepareObjectAnnotationDelete(annotationId, Instant.now(clock));
    }

    public Long pendingObjectAnnotationDeleteId() {
        return current().getPendingObjectAnnotationDeleteId();
    }

    public void clearObjectAnnotationDelete() {
        touches().objectAnnotationState = true;
        current().clearObjectAnnotationDelete(Instant.now(clock));
    }

    private ExchangeTouches touches() {
        ExchangeTouches current = exchangeTouches.get();
        if (current == null) {
            current = new ExchangeTouches();
            exchangeTouches.set(current);
        }
        return current;
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
        ConversationScopeKey scopeKey = scopeResolver.current(scope);
        return findCurrent(scope).orElseGet(() -> repository.save(
                ConversationContext.create(scope.channel(), scopeKey,
                        activeFocusId(scope, scopeKey).orElse(null), Instant.now(clock))));
    }

    private Optional<ConversationContext> findCurrent(WorkspaceContext scope) {
        ConversationScopeKey currentScope = scopeResolver.current(scope);
        Optional<UUID> focusId = activeFocusId(scope, currentScope);
        Optional<ConversationContext> current = focusId.map(id -> repository
                        .findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusId(
                                scope.workspaceId(), scope.actorId(), scope.channel(),
                                currentScope.digest(), id))
                .orElseGet(() -> repository
                        .findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                                scope.workspaceId(), scope.actorId(), scope.channel(),
                                currentScope.digest()));
        if (current.isPresent()) {
            validateStoredScope(current.get(), currentScope);
            return current;
        }
        if (focusId.isPresent()) {
            return Optional.empty();
        }
        return scopeResolver.previous(scope).flatMap(previousScope -> repository
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                        scope.workspaceId(), scope.actorId(), scope.channel(), previousScope.digest())
                .map(context -> {
                    validateStoredScope(context, previousScope);
                    context.migrateScope(currentScope, Instant.now(clock));
                    return context;
                }));
    }

    private Optional<UUID> activeFocusId(WorkspaceContext scope, ConversationScopeKey scopeKey) {
        if (focusRepository == null) {
            return Optional.empty();
        }
        return focusRepository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                scope.workspaceId(), scope.actorId(), scope.channel(), scopeKey.digest(),
                ConversationFocusStatus.ACTIVE).map(focus -> focus.getId());
    }

    private static void validateStoredScope(ConversationContext context, ConversationScopeKey expected) {
        if (!expected.digest().equals(context.getConversationScopeDigest())
                || expected.keyVersion() != context.getScopeKeyVersion()) {
            throw new IllegalStateException("conversation scope key version is not recognized");
        }
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

    private static final class ExchangeTouches {
        private boolean taskList;
        private boolean scheduleList;
        private boolean objectAnnotationState;
    }
}
