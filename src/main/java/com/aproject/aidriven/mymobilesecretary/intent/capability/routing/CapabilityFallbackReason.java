package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

/** Bounded reason values suitable for decision traces and low-cardinality metric tags. */
public enum CapabilityFallbackReason {
    NONE,
    MODE_LEGACY,
    NO_CANDIDATES,
    OUTSIDE_ALLOWLIST,
    AMBIGUOUS_ROLLOUT_BOUNDARY,
    ROUTING_ERROR
}
