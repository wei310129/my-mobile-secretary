package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Objects;

/** Internal-only failure classification. The detail must never be rendered as a public reply. */
public record ConversationDiagnostic(Code code, String detail) {

    public ConversationDiagnostic {
        Objects.requireNonNull(code, "code");
    }

    public enum Code {
        UNKNOWN_INTERPRETATION,
        BUSINESS_RULE_REJECTED,
        PRE_EXECUTION_FAILED,
        PROCESSING_RESULT_UNKNOWN,
        WORKSPACE_ROLE_DENIED
    }
}
