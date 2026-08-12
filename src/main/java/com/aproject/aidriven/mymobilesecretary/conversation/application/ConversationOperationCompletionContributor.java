package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Optional;
import java.util.UUID;

/** Capability-owned proof that a public operation is durably complete. */
public interface ConversationOperationCompletionContributor {

    String operationKind();

    Optional<Assessment> assess(UUID workflowId);

    enum Status {
        INCOMPLETE,
        COMPLETED
    }

    record Assessment(String operationKind, UUID workflowId, Status status) {

        public Assessment {
            if (operationKind == null || operationKind.isBlank()
                    || workflowId == null || status == null) {
                throw new IllegalArgumentException(
                        "Operation completion assessment is incomplete");
            }
        }
    }
}
