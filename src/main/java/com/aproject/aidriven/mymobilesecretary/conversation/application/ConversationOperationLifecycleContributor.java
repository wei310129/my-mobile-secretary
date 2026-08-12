package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceQuestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain-owned lifecycle adapter for one kind of unfinished conversation operation. */
public interface ConversationOperationLifecycleContributor {

    String operationKind();

    Optional<Operation> resolve(Reference reference);

    Optional<ResumeQuestion> resumeQuestion(Operation operation, String currentQuestionCode);

    default Optional<Operation> stage(IntentCommand command) {
        return Optional.empty();
    }

    default boolean supportsStaging(IntentCommand command) {
        return false;
    }

    default Optional<Operation> findCurrentUnfinished() {
        return Optional.empty();
    }

    default Optional<Operation> parent(Operation operation) {
        return Optional.empty();
    }

    default Optional<IntentResult> activate(Operation operation) {
        return Optional.empty();
    }

    CloseOutcome close(Operation operation);

    record Reference(
            String rootDomain, UUID workflowId, String routingKey, String safeLabel) {
    }

    record Operation(
            String operationKind,
            String rootDomain,
            UUID workflowId,
            String routingKey,
            String safeLabel,
            boolean unfinishedDomainState) {
    }

    record ResumeQuestion(
            String publicTopic,
            String publicProgress,
            String code,
            String slot,
            String prompt,
            int maxLength,
            PublicConversationChoiceQuestion choiceQuestion,
            LifecycleContext lifecycleContext) {

        public ResumeQuestion(
                String publicTopic,
                String publicProgress,
                String code,
                String slot,
                String prompt,
                int maxLength) {
            this(
                    publicTopic,
                    publicProgress,
                    code,
                    slot,
                    prompt,
                    maxLength,
                    null,
                    LifecycleContext.of(publicTopic, publicProgress, prompt));
        }

        public static ResumeQuestion choice(
                String publicTopic,
                String publicProgress,
                String slot,
                PublicConversationChoiceQuestion question,
                int maxLength) {
            return new ResumeQuestion(
                    publicTopic,
                    publicProgress,
                    question.code(),
                    slot,
                    question.prompt(),
                    maxLength,
                    question,
                    LifecycleContext.of(publicTopic, publicProgress, question.prompt()));
        }

        public static ResumeQuestion withContext(
                LifecycleContext context,
                String code,
                String slot,
                String prompt,
                int maxLength) {
            return new ResumeQuestion(
                    context.publicTopic(),
                    context.activeStep(),
                    code,
                    slot,
                    prompt,
                    maxLength,
                    null,
                    context);
        }

        public static ResumeQuestion choiceWithContext(
                LifecycleContext context,
                String slot,
                PublicConversationChoiceQuestion question,
                int maxLength) {
            return new ResumeQuestion(
                    context.publicTopic(),
                    context.activeStep(),
                    question.code(),
                    slot,
                    question.prompt(),
                    maxLength,
                    question,
                    context);
        }

        public ResumeQuestion {
            if (publicTopic == null || publicTopic.isBlank()
                    || publicProgress == null || publicProgress.isBlank()
                    || code == null || code.isBlank() || slot == null || slot.isBlank()
                    || prompt == null || prompt.isBlank() || maxLength < 1) {
                throw new IllegalArgumentException("resume context is incomplete");
            }
            if (choiceQuestion != null && !choiceQuestion.code().equals(code)) {
                throw new IllegalArgumentException("resume choice question code must match");
            }
            if (lifecycleContext == null
                    || !lifecycleContext.publicTopic().equals(publicTopic)
                    || !lifecycleContext.activeStep().equals(publicProgress)) {
                throw new IllegalArgumentException("resume lifecycle context must match");
            }
        }
    }

    record LifecycleContext(
            String publicTopic,
            String activeStep,
            List<String> preservedFacts,
            List<String> unresolvedFacts) {

        public LifecycleContext {
            if (publicTopic == null || publicTopic.isBlank()
                    || activeStep == null || activeStep.isBlank()) {
                throw new IllegalArgumentException("lifecycle topic and active step are required");
            }
            preservedFacts = normalizedFacts(preservedFacts, "preserved facts");
            unresolvedFacts = normalizedFacts(unresolvedFacts, "unresolved facts");
        }

        static LifecycleContext of(String topic, String activeStep, String unresolvedFact) {
            return new LifecycleContext(
                    topic,
                    activeStep,
                    List.of("目前已確認的資料與進度仍保留"),
                    List.of("仍需回覆下方唯一下一步"));
        }

        private static List<String> normalizedFacts(List<String> facts, String field) {
            List<String> normalized = facts == null
                    ? List.of()
                    : facts.stream()
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
