package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Registry boundary that prevents a lifecycle caller from guessing a domain operation. */
@Component
public final class ConversationOperationLifecycleRegistry {

    private final List<ConversationOperationLifecycleContributor> contributors;
    private final Map<String, ConversationOperationLifecycleContributor> byKind;

    public ConversationOperationLifecycleRegistry(List<ConversationOperationLifecycleContributor> contributors) {
        this.contributors = List.copyOf(contributors);
        this.byKind = this.contributors.stream().collect(Collectors.toUnmodifiableMap(
                contributor -> requiredKind(contributor.operationKind()), Function.identity(),
                (left, right) -> {
                    throw new IllegalArgumentException("duplicate conversation lifecycle contributor");
                }));
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> resolve(
            ConversationOperationLifecycleContributor.Reference reference) {
        return contributors.stream().map(contributor -> contributor.resolve(reference))
                .flatMap(Optional::stream).findFirst();
    }

    public Optional<ConversationOperationLifecycleContributor.ResumeQuestion> resumeQuestion(
            ConversationOperationLifecycleContributor.Operation operation, String questionCode) {
        return contributor(operation).resumeQuestion(operation, questionCode);
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> parent(
            ConversationOperationLifecycleContributor.Operation operation) {
        return contributor(operation).parent(operation);
    }

    public ConversationOperationLifecycleContributor.CloseOutcome close(
            ConversationOperationLifecycleContributor.Operation operation) {
        return contributor(operation).close(operation);
    }

    private ConversationOperationLifecycleContributor contributor(
            ConversationOperationLifecycleContributor.Operation operation) {
        Objects.requireNonNull(operation, "operation");
        ConversationOperationLifecycleContributor contributor = byKind.get(operation.operationKind());
        if (contributor == null) {
            throw new IllegalStateException("conversation lifecycle operation is unavailable");
        }
        return contributor;
    }

    private static String requiredKind(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("conversation lifecycle operation kind is required");
        }
        return value;
    }
}
