package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Objects;
import java.util.Set;

/** Maps every public result action to a reviewed tone/evidence template family. */
final class SecretaryReplyContractCatalog {

    private SecretaryReplyContractCatalog() {
    }

    static Contract forAction(IntentResult.Action action) {
        Objects.requireNonNull(action, "action");
        String name = action.name();
        if (action == IntentResult.Action.FEEDBACK_RECEIVED) {
            return contract("F-N01", ClaimClass.FEEDBACK);
        }
        if (action == IntentResult.Action.AI_UNAVAILABLE) {
            return contract("E03", ClaimClass.FAILURE);
        }
        if (action == IntentResult.Action.FAILURE_EXPLAINED) {
            return contract("E08", ClaimClass.QUERY);
        }
        if (action == IntentResult.Action.CALENDAR_CATEGORY_CHANGED_COLOR_UNSUPPORTED) {
            return contract("E02", ClaimClass.FAILURE);
        }
        if (action == IntentResult.Action.SOCIAL_REPLIED) {
            return contract("L18", ClaimClass.SOCIAL);
        }
        if (action == IntentResult.Action.PLACE_INFO
                || action == IntentResult.Action.PLACE_CATALOG_INFO
                || action == IntentResult.Action.PLACE_CATALOG_ADOPTED) {
            return new Contract("Q01", ClaimClass.QUERY,
                    Set.of(PublicReplyEvidence.ENTITY_RESOLVED));
        }
        if (name.startsWith("CAPABILITY_HELP_")) {
            return contract("L17", ClaimClass.HELP);
        }
        if (containsAny(name,
                "CLARIFICATION", "NEEDS_DECISION", "CONFIRMATION_REQUIRED", "PREVIEWED",
                "MATERIALIZATION_PROPOSED")) {
            return contract("C01", ClaimClass.NEEDS_INPUT);
        }
        if (containsAny(name,
                "LISTED", "INFO", "HISTORY", "SUMMARY", "CHECKED", "COMPARISON",
                "SUGGESTED", "SUGGESTION", "OVERVIEW", "COUNT", "STATUS", "EXTREMES", "RECOGNIZED",
                "EXPLAINED", "MODE", "GROUPED")) {
            return contract("Q02", ClaimClass.QUERY);
        }
        if (containsAny(name,
                "CREATED", "DEFERRED", "COMPLETED", "CANCELED", "RESCHEDULED", "SET",
                "BOUND", "ADDED", "REMOVED", "EXECUTED", "CONFIRMED", "RECORDED",
                "IMPORTED", "MATERIALIZED", "UPDATED", "PAUSED", "RESUMED", "SKIPPED",
                "PURCHASED", "CLEARED", "ADJUSTED", "RESTOCKED", "SAVED", "ARCHIVED",
                "REOPENED", "REPLACED", "UNLINKED", "DRAFTED", "DISCARDED", "RESIZED")) {
            return contract("M01", ClaimClass.MUTATION);
        }
        throw new IllegalStateException("unclassified public result action: " + name);
    }

    private static Contract contract(String templateId, ClaimClass claimClass) {
        return new Contract(templateId, claimClass, switch (claimClass) {
            case QUERY -> Set.of(PublicReplyEvidence.QUERY_COMPLETED);
            case NEEDS_INPUT -> Set.of(PublicReplyEvidence.PENDING_COMMITTED);
            case MUTATION -> Set.of(PublicReplyEvidence.MUTATION_COMMITTED);
            case FAILURE -> Set.of(PublicReplyEvidence.ZERO_MUTATION_VERIFIED);
            case FEEDBACK, SOCIAL, HELP -> Set.of(PublicReplyEvidence.ACKNOWLEDGED_ONLY);
        });
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    enum ClaimClass {
        QUERY,
        NEEDS_INPUT,
        MUTATION,
        FAILURE,
        FEEDBACK,
        SOCIAL,
        HELP
    }

    record Contract(
            String templateId,
            ClaimClass claimClass,
            Set<PublicReplyEvidence> defaultEvidence) {
        Contract {
            if (templateId == null || templateId.isBlank()) {
                throw new IllegalArgumentException("public reply template id is required");
            }
            Objects.requireNonNull(claimClass, "claim class");
            defaultEvidence = Set.copyOf(defaultEvidence);
            if (defaultEvidence.isEmpty()) {
                throw new IllegalArgumentException("public reply default evidence is required");
            }
        }
    }
}
