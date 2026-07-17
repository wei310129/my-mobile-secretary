package com.aproject.internal.aidispatcher.codex.cli;

record CodexCliProgress(
        boolean threadStarted,
        boolean turnStarted,
        boolean turnCompleted,
        boolean turnFailed,
        boolean errorObserved,
        String reportedThreadId,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens
) {
}
