package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes common lifecycle actions while keeping domain mutation authority with its contributor. */
@Service
@Transactional
public class ConversationOperationLifecycleService {

    private final ConversationOperationLifecycleRegistry registry;
    private final ConversationPendingQuestionService pendingQuestions;
    private final ConversationFocusService focuses;

    public ConversationOperationLifecycleService(ConversationOperationLifecycleRegistry registry,
                                                 ConversationPendingQuestionService pendingQuestions,
                                                 ConversationFocusService focuses) {
        this.registry = registry;
        this.pendingQuestions = pendingQuestions;
        this.focuses = focuses;
    }

    @Transactional(readOnly = true)
    public Optional<ConversationOperationLifecycleContributor.Operation> describeCurrent() {
        return focuses.activeFocus().flatMap(this::describe);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationOperationLifecycleContributor.ResumeQuestion> resumeQuestion() {
        return pendingQuestions.current().flatMap(pending -> describeCurrent().flatMap(operation ->
                registry.resumeQuestion(operation, pending.getQuestionCode())));
    }

    /**
     * Cancels a single unfinished operation. Pending cancellation is the durable replay fence;
     * contributor close is never invoked when that fence was already consumed.
     */
    public ClearResult closeCurrent(String inboundHmac) {
        Optional<ConversationOperationLifecycleContributor.Operation> operation = describeCurrent();
        if (operation.isEmpty() || !operation.get().unfinishedDomainState()) {
            return ClearResult.nothingClosed();
        }
        if (!pendingQuestions.cancelCurrent(operation.get().workflowId(), inboundHmac)) {
            return ClearResult.replayIgnoredResult();
        }
        ConversationOperationLifecycleContributor.CloseOutcome outcome = registry.close(operation.get());
        focuses.close(ConversationFocusCloseReason.USER_CLOSED, inboundHmac);
        return new ClearResult(true, outcome.domainMutationCount(), outcome.committedDataPreserved(), false);
    }

    private Optional<ConversationOperationLifecycleContributor.Operation> describe(ConversationFocus focus) {
        return registry.resolve(new ConversationOperationLifecycleContributor.Reference(
                focus.getRootDomain(), focus.getWorkflowId(), focus.getRoutingKey(), focus.getSafeLabel()));
    }

    public record ClearResult(boolean closed, int domainMutationCount, boolean committedDataPreserved,
                              boolean replayIgnored) {
        static ClearResult nothingClosed() {
            return new ClearResult(false, 0, true, false);
        }

        static ClearResult replayIgnoredResult() {
            return new ClearResult(false, 0, true, true);
        }
    }
}
