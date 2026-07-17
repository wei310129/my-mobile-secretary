package com.aproject.internal.aidispatcher.codex.cli;

final class CodexCliProtocolException extends RuntimeException {

    CodexCliProtocolException(String message) {
        super(message);
    }

    CodexCliProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
