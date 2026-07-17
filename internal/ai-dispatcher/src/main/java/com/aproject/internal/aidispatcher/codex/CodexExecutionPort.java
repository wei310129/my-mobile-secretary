package com.aproject.internal.aidispatcher.codex;

public interface CodexExecutionPort {

    CodexStartReceipt startCodex(CodexStartCommand command);

    default CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
        return CodexExecutionObservation.unknown("QUERY_NOT_SUPPORTED", query.requestedAt());
    }
}
