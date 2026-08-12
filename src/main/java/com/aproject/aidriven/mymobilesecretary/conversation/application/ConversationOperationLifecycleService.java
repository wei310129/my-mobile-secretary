package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Upper-layer start/resume/close boundary for typed conversation operations. */
@Service
public class ConversationOperationLifecycleService {

    private final ConversationOperationLifecycleRegistry registry;
    private final ConversationPendingQuestionService pendingQuestions;
    private final ConversationFocusService focuses;

    public ConversationOperationLifecycleService(
            ConversationOperationLifecycleRegistry registry,
            ConversationPendingQuestionService pendingQuestions,
            ConversationFocusService focuses) {
        this.registry = registry;
        this.pendingQuestions = pendingQuestions;
        this.focuses = focuses;
    }

    @Transactional(readOnly = true)
    public Optional<ConversationOperationLifecycleContributor.Operation> describe(
            ConversationPendingQuestion pending) {
        if (pending == null) return describeActiveFocus();
        return registry.resolve(new ConversationOperationLifecycleContributor.Reference(
                pending.getRootDomain(), pending.getWorkflowId(), null,
                pending.getWorkflowSafeLabel()));
    }

    @Transactional
    public Optional<ConversationOperationLifecycleContributor.Operation> describeCurrent() {
        Optional<ConversationPendingQuestion> pending = pendingQuestions.current();
        return pending.isPresent() ? describe(pending.orElseThrow()) : describeActiveFocus();
    }

    @Transactional
    public ClearResult closeCurrent(
            ConversationOperationLifecycleContributor.Operation operation,
            String inboundHmac) {
        ConversationOperationLifecycleContributor.CloseOutcome domain = registry.close(operation);
        boolean pendingCanceled = pendingQuestions.cancelCurrent(
                operation.workflowId(), inboundHmac);
        Optional<ConversationOperationLifecycleContributor.Operation> parent =
                registry.parent(operation);
        int focusCount = parent.isEmpty() ? focuses.closeAll(inboundHmac) : 0;
        return new ClearResult(
                operation.safeLabel(), domain.domainMutationCount(),
                pendingCanceled ? 1 : 0,
                focusCount,
                domain.committedDataPreserved(),
                parent.orElse(null));
    }

    @Transactional
    public ClearResult closeCurrentTree(
            ConversationOperationLifecycleContributor.Operation operation,
            String inboundHmac) {
        int domainCount = 0;
        ConversationOperationLifecycleContributor.Operation current = operation;
        Optional<ConversationOperationLifecycleContributor.Operation> parent;
        do {
            domainCount += registry.close(current).domainMutationCount();
            parent = registry.parent(current);
            if (parent.isPresent()) current = parent.orElseThrow();
        } while (parent.isPresent());
        boolean pendingCanceled = pendingQuestions.cancelCurrent(
                operation.workflowId(), inboundHmac);
        int focusCount = focuses.closeAll(inboundHmac);
        return new ClearResult(
                operation.safeLabel(), domainCount, pendingCanceled ? 1 : 0,
                focusCount, true, null);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationOperationLifecycleContributor.ResumeQuestion> resumeQuestion(
            ConversationOperationLifecycleContributor.Operation operation,
            String currentQuestionCode) {
        return registry.resumeQuestion(operation, currentQuestionCode);
    }

    @Transactional
    public Optional<ConversationOperationLifecycleContributor.Operation> stage(
            IntentCommand command) {
        return registry.stage(command);
    }

    @Transactional
    public Optional<IntentResult> activateDeferred(ConversationPendingQuestion pending) {
        if (pending == null || pending.getDeferredWorkflowId() == null) {
            return Optional.empty();
        }
        Optional<ConversationOperationLifecycleContributor.Operation> operation = registry.resolve(
                new ConversationOperationLifecycleContributor.Reference(
                        pending.getRootDomain(), pending.getDeferredWorkflowId(), null, null));
        return operation.flatMap(registry::activate);
    }

    @Transactional
    public int discardDeferred(ConversationPendingQuestion pending) {
        if (pending == null || pending.getDeferredWorkflowId() == null) return 0;
        ConversationOperationLifecycleContributor.Operation operation = registry.resolve(
                        new ConversationOperationLifecycleContributor.Reference(
                                pending.getRootDomain(), pending.getDeferredWorkflowId(), null, null))
                .orElseThrow(() -> new IllegalStateException(
                        "deferred operation lifecycle owner is unavailable"));
        return registry.close(operation).domainMutationCount();
    }

    @Transactional
    public int discardCurrentUnfinished(String operationKind) {
        return registry.findCurrentUnfinished(operationKind)
                .map(registry::close)
                .map(ConversationOperationLifecycleContributor.CloseOutcome::domainMutationCount)
                .orElse(0);
    }

    private Optional<ConversationOperationLifecycleContributor.Operation> describeActiveFocus() {
        return focuses.activeFocus().flatMap(this::describe);
    }

    private Optional<ConversationOperationLifecycleContributor.Operation> describe(
            ConversationFocus focus) {
        return registry.resolve(new ConversationOperationLifecycleContributor.Reference(
                focus.getRootDomain(), focus.getWorkflowId(), focus.getRoutingKey(),
                focus.getSafeLabel()));
    }

    public record ClearResult(
            String safeLabel,
            int domainMutationCount,
            int pendingMutationCount,
            int focusMutationCount,
            boolean committedDataPreserved,
            ConversationOperationLifecycleContributor.Operation resumedParent) {

        public ClearResult(
                String safeLabel,
                int domainMutationCount,
                int pendingMutationCount,
                int focusMutationCount,
                boolean committedDataPreserved) {
            this(
                    safeLabel,
                    domainMutationCount,
                    pendingMutationCount,
                    focusMutationCount,
                    committedDataPreserved,
                    null);
        }
    }
}
