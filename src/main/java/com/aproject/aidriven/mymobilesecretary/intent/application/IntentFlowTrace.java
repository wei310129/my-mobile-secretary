package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Small mutable request-local state used only to assemble the final decision trace. */
final class IntentFlowTrace {

    private String selectedCapability;
    private IntentDecisionTrace.ValidationOutcome validationOutcome =
            IntentDecisionTrace.ValidationOutcome.NOT_RUN;
    private String validationCode;
    private IntentDecisionTrace.ExecutionOutcome executionOutcome =
            IntentDecisionTrace.ExecutionOutcome.NOT_RUN;

    void select(IntentCommand command) {
        selectedCapability = command == null || command.type() == null
                ? null
                : command.type().name();
    }

    void selectBatch(List<IntentCommand> commands) {
        selectedCapability = commands.stream()
                .filter(Objects::nonNull)
                .map(IntentCommand::type)
                .filter(Objects::nonNull)
                .map(IntentCommand.Type::name)
                .limit(5)
                .collect(Collectors.joining(","));
        if (selectedCapability.isBlank()) {
            selectedCapability = null;
        }
    }

    void validationPassed() {
        if (validationOutcome == IntentDecisionTrace.ValidationOutcome.NOT_RUN) {
            validationOutcome = IntentDecisionTrace.ValidationOutcome.PASSED;
        }
    }

    void validationRejected(String code) {
        validationOutcome = IntentDecisionTrace.ValidationOutcome.REJECTED;
        validationCode = code;
    }

    void validationFailed(String code) {
        validationOutcome = IntentDecisionTrace.ValidationOutcome.FAILED;
        validationCode = code;
    }

    void unexpectedFailure() {
        executionOutcome = IntentDecisionTrace.ExecutionOutcome.FAILED;
        if (validationCode == null) {
            validationCode = "UNEXPECTED_INTENT_FAILURE";
        }
    }

    void complete(IntentResult result) {
        if (executionOutcome == IntentDecisionTrace.ExecutionOutcome.FAILED || result == null) {
            return;
        }
        executionOutcome = switch (result.action()) {
            case CLARIFICATION_NEEDED -> IntentDecisionTrace.ExecutionOutcome.CLARIFICATION;
            case AI_UNAVAILABLE, FALLBACK_TASK_CREATED -> IntentDecisionTrace.ExecutionOutcome.FALLBACK;
            default -> IntentDecisionTrace.ExecutionOutcome.SUCCEEDED;
        };
    }

    String redactedSummary(IntentResult result) {
        String action = result == null ? "NONE" : result.action().name();
        String capability = selectedCapability == null ? "DETERMINISTIC" : selectedCapability;
        String code = validationCode == null ? "NONE" : validationCode;
        return "action=%s; capability=%s; validationCode=%s"
                .formatted(action, capability, code);
    }

    String selectedCapability() {
        return selectedCapability;
    }

    IntentDecisionTrace.ValidationOutcome validationOutcome() {
        return validationOutcome;
    }

    String validationCode() {
        return validationCode;
    }

    IntentDecisionTrace.ExecutionOutcome executionOutcome() {
        return executionOutcome;
    }
}
