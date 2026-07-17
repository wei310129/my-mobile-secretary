package com.aproject.internal.aidispatcher.codex;

public interface CodexExecutionPort {

    CodexStartReceipt startCodex(CodexStartCommand command);
}
