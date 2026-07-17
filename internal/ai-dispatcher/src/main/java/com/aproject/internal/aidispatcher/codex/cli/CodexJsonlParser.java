package com.aproject.internal.aidispatcher.codex.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class CodexJsonlParser {

    private final ObjectMapper objectMapper;
    private final int maximumLineLength;
    private boolean threadStarted;
    private boolean turnStarted;
    private boolean turnCompleted;
    private boolean turnFailed;
    private boolean errorObserved;
    private String reportedThreadId;
    private long inputTokens;
    private long cachedInputTokens;
    private long outputTokens;
    private long reasoningOutputTokens;

    CodexJsonlParser(ObjectMapper objectMapper, int maximumLineLength) {
        this.objectMapper = objectMapper;
        if (maximumLineLength <= 0) {
            throw new IllegalArgumentException("maximumLineLength must be positive");
        }
        this.maximumLineLength = maximumLineLength;
    }

    void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (line.length() > maximumLineLength) {
            throw new CodexCliProtocolException("Codex JSONL line exceeds configured limit");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(line);
        } catch (JsonProcessingException invalidJson) {
            throw new CodexCliProtocolException("Codex emitted malformed JSONL", invalidJson);
        }
        String type = event.path("type").asText("");
        switch (type) {
            case "thread.started" -> {
                threadStarted = true;
                reportedThreadId = nullableText(event.path("thread_id"));
            }
            case "turn.started" -> turnStarted = true;
            case "turn.completed" -> {
                turnCompleted = true;
                JsonNode usage = event.path("usage");
                inputTokens = nonNegativeLong(usage, "input_tokens");
                cachedInputTokens = nonNegativeLong(usage, "cached_input_tokens");
                outputTokens = nonNegativeLong(usage, "output_tokens");
                reasoningOutputTokens = nonNegativeLong(usage, "reasoning_output_tokens");
            }
            case "turn.failed" -> turnFailed = true;
            case "error" -> errorObserved = true;
            default -> {
                // Forward-compatible: item.* and newly introduced event types are progress only.
            }
        }
    }

    CodexCliProgress snapshot() {
        return new CodexCliProgress(
                threadStarted, turnStarted, turnCompleted, turnFailed, errorObserved,
                reportedThreadId, inputTokens, cachedInputTokens,
                outputTokens, reasoningOutputTokens);
    }

    private static String nullableText(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static long nonNegativeLong(JsonNode parent, String field) {
        long value = parent.path(field).asLong(0);
        return Math.max(0, value);
    }
}
