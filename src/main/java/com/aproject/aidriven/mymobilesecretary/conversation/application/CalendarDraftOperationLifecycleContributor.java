package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Lifecycle adapter for a typed, non-route Calendar draft staged during context choice. */
@Component
public final class CalendarDraftOperationLifecycleContributor
        implements ConversationOperationLifecycleContributor {

    static final String KIND = "calendar-draft";
    private final CalendarIntentDraftService drafts;
    private final CalendarIntentDraftConversationService conversations;

    public CalendarDraftOperationLifecycleContributor(
            CalendarIntentDraftService drafts,
            CalendarIntentDraftConversationService conversations) {
        this.drafts = drafts;
        this.conversations = conversations;
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public boolean supportsStaging(IntentCommand command) {
        return command != null && command.type() == IntentCommand.Type.CREATE_SCHEDULE;
    }

    @Override
    public Optional<Operation> stage(IntentCommand command) {
        return conversations.stageCalendarForContextChoice(command).map(this::operation);
    }

    @Override
    public Optional<Operation> resolve(Reference reference) {
        if (reference == null || reference.workflowId() == null) return Optional.empty();
        return drafts.findAvailableForLifecycle(reference.workflowId())
                .filter(CalendarDraftOperationLifecycleContributor::isCalendarDraft)
                .map(this::operation);
    }

    @Override
    public Optional<IntentResult> activate(Operation operation) {
        return resolve(new Reference(
                        operation.rootDomain(), operation.workflowId(),
                        operation.routingKey(), operation.safeLabel()))
                .map(value -> conversations.activateStagedCalendarDraft(value.workflowId()));
    }

    @Override
    public Optional<ResumeQuestion> resumeQuestion(
            Operation operation, String currentQuestionCode) {
        DraftView draft = drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(CalendarDraftOperationLifecycleContributor::isCalendarDraft)
                .orElseThrow(() -> new IllegalStateException("calendar draft is unavailable"));
        return Optional.of(new ResumeQuestion(
                draft.title(),
                "已保留時間與行程內容，尚未放進行事曆。",
                "calendar.confirm",
                "calendar.confirm",
                "要照這個版本建立嗎？",
                20));
    }

    @Override
    public CloseOutcome close(Operation operation) {
        DraftView draft = drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(CalendarDraftOperationLifecycleContributor::isCalendarDraft)
                .orElseThrow(() -> new IllegalStateException("calendar draft is unavailable"));
        if (draft.status() == Status.PENDING) {
            drafts.discard(draft.id(), draft.revision());
            return new CloseOutcome(1, true);
        }
        return new CloseOutcome(0, true);
    }

    private Operation operation(DraftView draft) {
        return new Operation(
                KIND,
                CalendarIntentDraftConversationService.FOCUS_DOMAIN,
                draft.id(),
                null,
                draft.title(),
                draft.status() == Status.PENDING);
    }

    private static boolean isCalendarDraft(DraftView draft) {
        return draft.routeJourneyKind() == null;
    }
}
