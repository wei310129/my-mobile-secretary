package com.aproject.aidriven.mymobilesecretary.intent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Privacy-aware record of the routing, validation and execution decision for one request. */
@Entity
@Table(name = "intent_decision_trace")
public class IntentDecisionTrace {

    public enum ValidationOutcome {
        NOT_RUN, PASSED, REJECTED, FAILED
    }

    public enum ExecutionOutcome {
        NOT_RUN, SUCCEEDED, CLARIFICATION, REJECTED, FALLBACK, FAILED
    }

    private static final int MAX_CHANNEL = 40;
    private static final int MAX_VERSION = 100;
    private static final int MAX_CAPABILITY = 160;
    private static final int MAX_MODEL = 160;
    private static final int MAX_KEY_ID = 120;
    private static final int MAX_VALIDATION_CODE = 80;
    private static final int MAX_HASH = 64;
    private static final int MAX_CONTEXT_KEY = 100;
    private static final int MAX_MAP_ENTRIES = 32;
    private static final int MAX_SUMMARY = 4_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID requestId;

    private UUID workspaceId;

    private UUID actorId;

    @Column(nullable = false, length = MAX_CHANNEL)
    private String channel;

    @Column(length = MAX_VERSION)
    private String routerVersion;

    @Column(length = MAX_VERSION)
    private String promptVersion;

    @Column(length = MAX_VERSION)
    private String capabilityVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Double> candidateSummary;

    @Column(length = MAX_VERSION)
    private String shadowRouterVersion;

    @Column(length = 40)
    private String shadowDisposition;

    @Column(length = MAX_VALIDATION_CODE)
    private String shadowFallbackReason;

    @Column(length = MAX_VERSION)
    private String shadowPromptVersion;

    @Column(length = MAX_HASH)
    private String shadowPromptHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> shadowTokenEstimate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> shadowContextPlan;

    @Column(length = MAX_CAPABILITY)
    private String selectedCapability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ValidationOutcome validationOutcome;

    @Column(length = MAX_VALIDATION_CODE)
    private String validationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExecutionOutcome executionOutcome;

    @Column(length = MAX_MODEL)
    private String model;

    private Integer inputTokens;

    private Integer outputTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Long> stageLatenciesMs;

    @Column(columnDefinition = "bytea")
    private byte[] rawInputEncrypted;

    @Column(columnDefinition = "bytea")
    private byte[] rawOutputEncrypted;

    @Column(length = MAX_KEY_ID)
    private String rawCipherKeyId;

    @Column(columnDefinition = "text")
    private String redactedSummary;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant rawExpiresAt;

    @Column(nullable = false)
    private Instant summaryExpiresAt;

    protected IntentDecisionTrace() {
    }

    private IntentDecisionTrace(IntentDecisionTraceDraft draft, byte[] rawInputEncrypted,
            byte[] rawOutputEncrypted, String rawCipherKeyId, Instant createdAt,
            Instant rawExpiresAt, Instant summaryExpiresAt) {
        this.requestId = draft.requestId();
        this.workspaceId = draft.workspaceId();
        this.actorId = draft.actorId();
        this.channel = truncate(draft.channel(), MAX_CHANNEL);
        this.routerVersion = truncate(draft.routerVersion(), MAX_VERSION);
        this.promptVersion = truncate(draft.promptVersion(), MAX_VERSION);
        this.capabilityVersion = truncate(draft.capabilityVersion(), MAX_VERSION);
        this.candidateSummary = sanitizeCandidates(draft.candidateScores());
        this.shadowRouterVersion = truncate(draft.shadowRouterVersion(), MAX_VERSION);
        this.shadowDisposition = truncate(draft.shadowDisposition(), 40);
        this.shadowFallbackReason = truncate(draft.shadowFallbackReason(), MAX_VALIDATION_CODE);
        this.shadowPromptVersion = truncate(draft.shadowPromptVersion(), MAX_VERSION);
        this.shadowPromptHash = truncate(draft.shadowPromptHash(), MAX_HASH);
        this.shadowTokenEstimate = sanitizeNonNegativeIntegers(draft.shadowTokenEstimate());
        this.shadowContextPlan = sanitizeContextPlan(draft.shadowContextPlan());
        this.selectedCapability = truncate(draft.selectedCapability(), MAX_CAPABILITY);
        this.validationOutcome = draft.validationOutcome();
        this.validationCode = truncate(draft.validationCode(), MAX_VALIDATION_CODE);
        this.executionOutcome = draft.executionOutcome();
        this.model = truncate(draft.model(), MAX_MODEL);
        this.inputTokens = draft.inputTokens();
        this.outputTokens = draft.outputTokens();
        this.stageLatenciesMs = sanitizeLatencies(draft.stageLatenciesMs());
        this.rawInputEncrypted = copy(rawInputEncrypted);
        this.rawOutputEncrypted = copy(rawOutputEncrypted);
        boolean hasRaw = rawInputEncrypted != null || rawOutputEncrypted != null;
        this.rawCipherKeyId = hasRaw ? truncate(rawCipherKeyId, MAX_KEY_ID) : null;
        this.redactedSummary = truncate(draft.redactedSummary(), MAX_SUMMARY);
        this.createdAt = createdAt;
        this.rawExpiresAt = hasRaw ? rawExpiresAt : null;
        this.summaryExpiresAt = summaryExpiresAt;
    }

    public static IntentDecisionTrace capture(IntentDecisionTraceDraft draft,
            byte[] rawInputEncrypted, byte[] rawOutputEncrypted, String rawCipherKeyId,
            Instant createdAt, Instant rawExpiresAt, Instant summaryExpiresAt) {
        return new IntentDecisionTrace(draft, rawInputEncrypted, rawOutputEncrypted,
                rawCipherKeyId, createdAt, rawExpiresAt, summaryExpiresAt);
    }

    private static Map<String, Double> sanitizeCandidates(Map<String, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (result.size() < MAX_MAP_ENTRIES && key != null && !key.isBlank()
                    && value != null && Double.isFinite(value)) {
                result.put(truncate(key, MAX_CAPABILITY), value);
            }
        });
        return result;
    }

    private static Map<String, Long> sanitizeLatencies(Map<String, Long> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (result.size() < MAX_MAP_ENTRIES && key != null && !key.isBlank()
                    && value != null && value >= 0) {
                result.put(truncate(key, MAX_VERSION), value);
            }
        });
        return result;
    }

    private static Map<String, Integer> sanitizeNonNegativeIntegers(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (result.size() < MAX_MAP_ENTRIES && key != null && !key.isBlank()
                    && value != null && value >= 0) {
                result.put(truncate(key, MAX_VERSION), value);
            }
        });
        return result;
    }

    private static List<String> sanitizeContextPlan(List<String> source) {
        List<String> result = new ArrayList<>();
        for (String value : source) {
            if (result.size() >= MAX_MAP_ENTRIES) {
                break;
            }
            if (value != null && !value.isBlank()) {
                String bounded = truncate(value, MAX_CONTEXT_KEY);
                if (!result.contains(bounded)) {
                    result.add(bounded);
                }
            }
        }
        return result;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    public Long getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getChannel() {
        return channel;
    }

    public String getRouterVersion() {
        return routerVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    public Map<String, Double> getCandidateSummary() {
        return Map.copyOf(candidateSummary);
    }

    public String getShadowRouterVersion() {
        return shadowRouterVersion;
    }

    public String getShadowDisposition() {
        return shadowDisposition;
    }

    public String getShadowFallbackReason() {
        return shadowFallbackReason;
    }

    public String getShadowPromptVersion() {
        return shadowPromptVersion;
    }

    public String getShadowPromptHash() {
        return shadowPromptHash;
    }

    public Map<String, Integer> getShadowTokenEstimate() {
        return Map.copyOf(shadowTokenEstimate);
    }

    public List<String> getShadowContextPlan() {
        return List.copyOf(shadowContextPlan);
    }

    public String getSelectedCapability() {
        return selectedCapability;
    }

    public ValidationOutcome getValidationOutcome() {
        return validationOutcome;
    }

    public String getValidationCode() {
        return validationCode;
    }

    public ExecutionOutcome getExecutionOutcome() {
        return executionOutcome;
    }

    public String getModel() {
        return model;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Map<String, Long> getStageLatenciesMs() {
        return Map.copyOf(stageLatenciesMs);
    }

    public byte[] getRawInputEncrypted() {
        return copy(rawInputEncrypted);
    }

    public byte[] getRawOutputEncrypted() {
        return copy(rawOutputEncrypted);
    }

    public String getRawCipherKeyId() {
        return rawCipherKeyId;
    }

    public String getRedactedSummary() {
        return redactedSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRawExpiresAt() {
        return rawExpiresAt;
    }

    public Instant getSummaryExpiresAt() {
        return summaryExpiresAt;
    }
}
