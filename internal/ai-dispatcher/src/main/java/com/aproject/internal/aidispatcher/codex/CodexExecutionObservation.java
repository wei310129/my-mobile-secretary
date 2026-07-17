package com.aproject.internal.aidispatcher.codex;

import java.time.Instant;

public record CodexExecutionObservation(
        Status status,
        String externalExecutionId,
        String resultCode,
        Instant observedAt,
        Instant completedAt
) {
    public CodexExecutionObservation {
        if (status == null || observedAt == null) {
            throw new IllegalArgumentException("status and observedAt are required");
        }
        externalExecutionId = bounded(externalExecutionId, 200);
        resultCode = bounded(resultCode, 100);
        if ((status == Status.SUCCEEDED || status == Status.FAILED) && completedAt == null) {
            throw new IllegalArgumentException("completedAt is required for a terminal observation");
        }
    }

    public static CodexExecutionObservation running(String externalExecutionId, Instant observedAt) {
        return new CodexExecutionObservation(
                Status.RUNNING, externalExecutionId, null, observedAt, null);
    }

    public static CodexExecutionObservation succeeded(
            String externalExecutionId, String resultCode, Instant completedAt) {
        return new CodexExecutionObservation(
                Status.SUCCEEDED, externalExecutionId, resultCode, completedAt, completedAt);
    }

    public static CodexExecutionObservation failed(
            String externalExecutionId, String resultCode, Instant completedAt) {
        return new CodexExecutionObservation(
                Status.FAILED, externalExecutionId, resultCode, completedAt, completedAt);
    }

    public static CodexExecutionObservation notFound(String resultCode, Instant observedAt) {
        return new CodexExecutionObservation(
                Status.NOT_FOUND, null, resultCode, observedAt, null);
    }

    public static CodexExecutionObservation unknown(String resultCode, Instant observedAt) {
        return new CodexExecutionObservation(
                Status.UNKNOWN, null, resultCode, observedAt, null);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        NOT_FOUND,
        UNKNOWN
    }
}
