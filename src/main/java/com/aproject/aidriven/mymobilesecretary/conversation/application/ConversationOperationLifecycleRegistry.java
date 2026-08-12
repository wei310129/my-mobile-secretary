package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Resolves one exact typed operation and refuses absent, duplicate, or ambiguous ownership. */
@Component
public final class ConversationOperationLifecycleRegistry {

    private final List<ConversationOperationLifecycleContributor> contributors;
    private final Map<String, ConversationOperationLifecycleContributor> byKind;

    public ConversationOperationLifecycleRegistry(
            List<ConversationOperationLifecycleContributor> contributors) {
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        this.byKind = this.contributors.stream().collect(Collectors.toUnmodifiableMap(
                contributor -> requiredKind(contributor.operationKind()),
                Function.identity(),
                (first, duplicate) -> {
                    throw new IllegalStateException(
                            "duplicate operation lifecycle contributor for "
                                    + first.operationKind());
                }));
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> resolve(
            ConversationOperationLifecycleContributor.Reference reference) {
        List<ConversationOperationLifecycleContributor.Operation> matches = contributors.stream()
                .map(contributor -> contributor.resolve(reference))
                .flatMap(Optional::stream)
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous operation lifecycle ownership");
        }
        return matches.stream().findFirst();
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> stage(
            IntentCommand command) {
        List<ConversationOperationLifecycleContributor> matches = contributors.stream()
                .filter(contributor -> contributor.supportsStaging(command))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous staged operation lifecycle ownership");
        }
        return matches.stream().findFirst().flatMap(contributor -> contributor.stage(command));
    }

    public Optional<IntentResult> activate(
            ConversationOperationLifecycleContributor.Operation operation) {
        ConversationOperationLifecycleContributor contributor =
                byKind.get(requiredKind(operation.operationKind()));
        if (contributor == null) {
            throw new IllegalStateException("operation lifecycle contributor is unavailable");
        }
        return contributor.activate(operation);
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> findCurrentUnfinished(
            String operationKind) {
        ConversationOperationLifecycleContributor contributor =
                byKind.get(requiredKind(operationKind));
        return contributor == null ? Optional.empty() : contributor.findCurrentUnfinished();
    }

    public ConversationOperationLifecycleContributor.CloseOutcome close(
            ConversationOperationLifecycleContributor.Operation operation) {
        ConversationOperationLifecycleContributor contributor =
                byKind.get(requiredKind(operation.operationKind()));
        if (contributor == null) {
            throw new IllegalStateException("operation lifecycle contributor is unavailable");
        }
        return contributor.close(operation);
    }

    public Optional<ConversationOperationLifecycleContributor.Operation> parent(
            ConversationOperationLifecycleContributor.Operation operation) {
        ConversationOperationLifecycleContributor contributor =
                byKind.get(requiredKind(operation.operationKind()));
        if (contributor == null) {
            throw new IllegalStateException("operation lifecycle contributor is unavailable");
        }
        return contributor.parent(operation);
    }

    public Optional<ConversationOperationLifecycleContributor.ResumeQuestion> resumeQuestion(
            ConversationOperationLifecycleContributor.Operation operation,
            String currentQuestionCode) {
        ConversationOperationLifecycleContributor contributor =
                byKind.get(requiredKind(operation.operationKind()));
        if (contributor == null) {
            throw new IllegalStateException("operation lifecycle contributor is unavailable");
        }
        return contributor.resumeQuestion(operation, currentQuestionCode);
    }

    private static String requiredKind(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,39}")) {
            throw new IllegalArgumentException("operation kind must be a stable code");
        }
        return value;
    }
}
