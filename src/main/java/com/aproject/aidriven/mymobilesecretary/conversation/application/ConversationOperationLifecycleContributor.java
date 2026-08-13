package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain-owned adapter for one unfinished conversation operation.
 *
 * <p>The shared lifecycle knows no Calendar, route provider, LLM, LINE, or public reply
 * implementation. A contributor owns its typed domain mutation and exposes only safe progress
 * facts needed to resume a conversation.</p>
 */
public interface ConversationOperationLifecycleContributor {

    String operationKind();

    Optional<Operation> resolve(Reference reference);

    Optional<ResumeQuestion> resumeQuestion(Operation operation, String currentQuestionCode);

    default Optional<Operation> parent(Operation operation) {
        return Optional.empty();
    }

    /** Must be idempotent for the operation's durable state. */
    CloseOutcome close(Operation operation);

    record Reference(String rootDomain, UUID workflowId, String routingKey, String safeLabel) {
        public Reference {
            if (rootDomain == null || rootDomain.isBlank()) {
                throw new IllegalArgumentException("root domain is required");
            }
            if (workflowId == null && (routingKey == null || routingKey.isBlank())) {
                throw new IllegalArgumentException("workflow id or routing key is required");
            }
        }
    }

    record Operation(String operationKind, String rootDomain, UUID workflowId, String routingKey,
                     String safeLabel, boolean unfinishedDomainState) {
        public Operation {
            if (operationKind == null || operationKind.isBlank()
                    || rootDomain == null || rootDomain.isBlank()
                    || (workflowId == null && (routingKey == null || routingKey.isBlank()))) {
                throw new IllegalArgumentException("operation identity is incomplete");
            }
        }
    }

    record ResumeQuestion(String questionCode, String slot, String prompt, int maxLength,
                          LifecycleContext lifecycleContext) {
        public ResumeQuestion {
            if (questionCode == null || questionCode.isBlank() || slot == null || slot.isBlank()
                    || prompt == null || prompt.isBlank() || maxLength < 1
                    || lifecycleContext == null) {
                throw new IllegalArgumentException("resume question is incomplete");
            }
        }
    }

    record LifecycleContext(String publicTopic, String activeStep, List<String> preservedFacts,
                            List<String> unresolvedFacts) {
        public LifecycleContext {
            if (publicTopic == null || publicTopic.isBlank()
                    || activeStep == null || activeStep.isBlank()) {
                throw new IllegalArgumentException("lifecycle topic and active step are required");
            }
            preservedFacts = normalizedFacts(preservedFacts, "preserved facts");
            unresolvedFacts = normalizedFacts(unresolvedFacts, "unresolved facts");
        }

        private static List<String> normalizedFacts(List<String> facts, String field) {
            List<String> normalized = facts == null ? List.of() : facts.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " are required");
            }
            return List.copyOf(normalized);
        }
    }

    record CloseOutcome(int domainMutationCount, boolean committedDataPreserved) {
        public CloseOutcome {
            if (domainMutationCount < 0) {
                throw new IllegalArgumentException("domain mutation count cannot be negative");
            }
        }
    }
}
