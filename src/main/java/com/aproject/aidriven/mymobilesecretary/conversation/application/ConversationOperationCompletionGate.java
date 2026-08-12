package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Shared fail-closed gate for public operation-completion claims. */
@Component
public final class ConversationOperationCompletionGate {

    private final Map<String, ConversationOperationCompletionContributor> contributors;

    public ConversationOperationCompletionGate(
            List<ConversationOperationCompletionContributor> contributors) {
        this.contributors = contributors.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ConversationOperationCompletionContributor::operationKind,
                        Function.identity()));
    }

    public ConversationOperationCompletionContributor.Assessment requireCompleted(
            String operationKind, UUID workflowId) {
        ConversationOperationCompletionContributor contributor =
                contributors.get(operationKind);
        if (contributor == null) {
            throw new IllegalStateException(
                    "No completion contributor owns this operation kind");
        }
        ConversationOperationCompletionContributor.Assessment assessment = contributor
                .assess(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                        "The operation completion owner is unavailable"));
        if (assessment.status()
                != ConversationOperationCompletionContributor.Status.COMPLETED) {
            throw new IllegalStateException(
                    "The operation is not complete enough for a public completion claim");
        }
        return assessment;
    }
}
