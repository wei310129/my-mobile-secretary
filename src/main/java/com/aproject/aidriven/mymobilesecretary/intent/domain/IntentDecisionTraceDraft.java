package com.aproject.aidriven.mymobilesecretary.intent.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Data captured at the intent boundary before it is encrypted and persisted.
 *
 * <p>This class intentionally redacts {@link #toString()} because it temporarily holds raw model
 * input/output. Use the builder so new trace fields can be added without a long positional
 * constructor at every call site.</p>
 */
public final class IntentDecisionTraceDraft {

    private final UUID requestId;
    private final UUID workspaceId;
    private final UUID actorId;
    private final String channel;
    private final String routerVersion;
    private final String promptVersion;
    private final String capabilityVersion;
    private final Map<String, Double> candidateScores;
    private final String shadowRouterVersion;
    private final String shadowDisposition;
    private final String shadowFallbackReason;
    private final String shadowPromptVersion;
    private final String shadowPromptHash;
    private final Map<String, Integer> shadowTokenEstimate;
    private final List<String> shadowContextPlan;
    private final String selectedCapability;
    private final IntentDecisionTrace.ValidationOutcome validationOutcome;
    private final String validationCode;
    private final IntentDecisionTrace.ExecutionOutcome executionOutcome;
    private final String model;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Map<String, Long> stageLatenciesMs;
    private final String rawInput;
    private final String rawOutput;
    private final String redactedSummary;

    private IntentDecisionTraceDraft(Builder builder) {
        this.requestId = Objects.requireNonNull(builder.requestId, "requestId");
        if (builder.channel == null || builder.channel.isBlank()) {
            throw new IllegalArgumentException("Intent trace channel is required");
        }
        this.workspaceId = builder.workspaceId;
        this.actorId = builder.actorId;
        this.channel = builder.channel;
        this.routerVersion = builder.routerVersion;
        this.promptVersion = builder.promptVersion;
        this.capabilityVersion = builder.capabilityVersion;
        this.candidateScores = Map.copyOf(builder.candidateScores);
        this.shadowRouterVersion = builder.shadowRouterVersion;
        this.shadowDisposition = builder.shadowDisposition;
        this.shadowFallbackReason = builder.shadowFallbackReason;
        this.shadowPromptVersion = builder.shadowPromptVersion;
        this.shadowPromptHash = builder.shadowPromptHash;
        this.shadowTokenEstimate = Map.copyOf(builder.shadowTokenEstimate);
        this.shadowContextPlan = List.copyOf(builder.shadowContextPlan);
        this.selectedCapability = builder.selectedCapability;
        this.validationOutcome = Objects.requireNonNull(builder.validationOutcome, "validationOutcome");
        this.validationCode = builder.validationCode;
        this.executionOutcome = Objects.requireNonNull(builder.executionOutcome, "executionOutcome");
        this.model = builder.model;
        this.inputTokens = nonNegative(builder.inputTokens, "inputTokens");
        this.outputTokens = nonNegative(builder.outputTokens, "outputTokens");
        this.stageLatenciesMs = Map.copyOf(builder.stageLatenciesMs);
        this.rawInput = builder.rawInput;
        this.rawOutput = builder.rawOutput;
        this.redactedSummary = builder.redactedSummary;
    }

    public static Builder builder(UUID requestId, String channel) {
        return new Builder(requestId, channel);
    }

    private static Integer nonNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }

    public UUID requestId() {
        return requestId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID actorId() {
        return actorId;
    }

    public String channel() {
        return channel;
    }

    public String routerVersion() {
        return routerVersion;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String capabilityVersion() {
        return capabilityVersion;
    }

    public Map<String, Double> candidateScores() {
        return candidateScores;
    }

    public String shadowRouterVersion() {
        return shadowRouterVersion;
    }

    public String shadowDisposition() {
        return shadowDisposition;
    }

    public String shadowFallbackReason() {
        return shadowFallbackReason;
    }

    public String shadowPromptVersion() {
        return shadowPromptVersion;
    }

    public String shadowPromptHash() {
        return shadowPromptHash;
    }

    public Map<String, Integer> shadowTokenEstimate() {
        return shadowTokenEstimate;
    }

    public List<String> shadowContextPlan() {
        return shadowContextPlan;
    }

    public String selectedCapability() {
        return selectedCapability;
    }

    public IntentDecisionTrace.ValidationOutcome validationOutcome() {
        return validationOutcome;
    }

    public String validationCode() {
        return validationCode;
    }

    public IntentDecisionTrace.ExecutionOutcome executionOutcome() {
        return executionOutcome;
    }

    public String model() {
        return model;
    }

    public Integer inputTokens() {
        return inputTokens;
    }

    public Integer outputTokens() {
        return outputTokens;
    }

    public Map<String, Long> stageLatenciesMs() {
        return stageLatenciesMs;
    }

    public String rawInput() {
        return rawInput;
    }

    public String rawOutput() {
        return rawOutput;
    }

    public String redactedSummary() {
        return redactedSummary;
    }

    @Override
    public String toString() {
        return "IntentDecisionTraceDraft[requestId=%s, channel=%s, raw=REDACTED]"
                .formatted(requestId, channel);
    }

    public static final class Builder {

        private final UUID requestId;
        private final String channel;
        private UUID workspaceId;
        private UUID actorId;
        private String routerVersion;
        private String promptVersion;
        private String capabilityVersion;
        private final Map<String, Double> candidateScores = new LinkedHashMap<>();
        private String shadowRouterVersion;
        private String shadowDisposition;
        private String shadowFallbackReason;
        private String shadowPromptVersion;
        private String shadowPromptHash;
        private final Map<String, Integer> shadowTokenEstimate = new LinkedHashMap<>();
        private final List<String> shadowContextPlan = new ArrayList<>();
        private String selectedCapability;
        private IntentDecisionTrace.ValidationOutcome validationOutcome =
                IntentDecisionTrace.ValidationOutcome.NOT_RUN;
        private String validationCode;
        private IntentDecisionTrace.ExecutionOutcome executionOutcome =
                IntentDecisionTrace.ExecutionOutcome.NOT_RUN;
        private String model;
        private Integer inputTokens;
        private Integer outputTokens;
        private final Map<String, Long> stageLatenciesMs = new LinkedHashMap<>();
        private String rawInput;
        private String rawOutput;
        private String redactedSummary;

        private Builder(UUID requestId, String channel) {
            this.requestId = requestId;
            this.channel = channel;
        }

        public Builder workspaceId(UUID workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder actorId(UUID actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder versions(String routerVersion, String promptVersion, String capabilityVersion) {
            this.routerVersion = routerVersion;
            this.promptVersion = promptVersion;
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        public Builder candidate(String capabilityId, double score) {
            if (capabilityId != null && !capabilityId.isBlank() && Double.isFinite(score)) {
                candidateScores.put(capabilityId, score);
            }
            return this;
        }

        public Builder candidates(Map<String, Double> candidateScores) {
            if (candidateScores != null) {
                candidateScores.forEach((id, score) -> {
                    if (score != null) {
                        candidate(id, score);
                    }
                });
            }
            return this;
        }

        public Builder shadowRouting(
                String routerVersion,
                String disposition,
                String fallbackReason,
                String promptVersion,
                String promptHash,
                Map<String, Integer> tokenEstimate,
                Collection<String> contextPlan) {
            this.shadowRouterVersion = routerVersion;
            this.shadowDisposition = disposition;
            this.shadowFallbackReason = fallbackReason;
            this.shadowPromptVersion = promptVersion;
            this.shadowPromptHash = promptHash;
            this.shadowTokenEstimate.clear();
            if (tokenEstimate != null) {
                tokenEstimate.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null && value >= 0) {
                        this.shadowTokenEstimate.put(key, value);
                    }
                });
            }
            this.shadowContextPlan.clear();
            if (contextPlan != null) {
                contextPlan.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .forEach(this.shadowContextPlan::add);
            }
            return this;
        }

        public Builder selectedCapability(String selectedCapability) {
            this.selectedCapability = selectedCapability;
            return this;
        }

        public Builder validationOutcome(IntentDecisionTrace.ValidationOutcome validationOutcome) {
            this.validationOutcome = validationOutcome;
            return this;
        }

        public Builder validationCode(String validationCode) {
            this.validationCode = validationCode;
            return this;
        }

        public Builder executionOutcome(IntentDecisionTrace.ExecutionOutcome executionOutcome) {
            this.executionOutcome = executionOutcome;
            return this;
        }

        public Builder modelUsage(String model, Integer inputTokens, Integer outputTokens) {
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder stageLatency(String stage, long milliseconds) {
            if (stage != null && !stage.isBlank() && milliseconds >= 0) {
                stageLatenciesMs.put(stage, milliseconds);
            }
            return this;
        }

        public Builder rawExchange(String rawInput, String rawOutput) {
            this.rawInput = rawInput;
            this.rawOutput = rawOutput;
            return this;
        }

        public Builder redactedSummary(String redactedSummary) {
            this.redactedSummary = redactedSummary;
            return this;
        }

        public IntentDecisionTraceDraft build() {
            return new IntentDecisionTraceDraft(this);
        }
    }
}
