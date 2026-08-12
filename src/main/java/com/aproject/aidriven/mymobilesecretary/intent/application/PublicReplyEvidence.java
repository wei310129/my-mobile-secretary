package com.aproject.aidriven.mymobilesecretary.intent.application;

/** Verified application state that permits a public reply to make a concrete commitment claim. */
public enum PublicReplyEvidence {
    ACKNOWLEDGED_ONLY,
    ENTITY_RESOLVED,
    QUERY_COMPLETED,
    ZERO_MUTATION_VERIFIED,
    PENDING_COMMITTED,
    PREFERENCE_COMMITTED,
    MUTATION_COMMITTED,
    REPAIR_STARTED,
    REPAIR_COMPLETED,
    ASYNC_COMMITTED,
    NOTIFICATION_COMMITTED,
    REPLAY_VERIFIED
}
