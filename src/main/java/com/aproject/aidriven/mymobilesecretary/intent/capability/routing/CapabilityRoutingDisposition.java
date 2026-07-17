package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

/** Indicates which interpretation path a caller may continue with; it never authorizes execution. */
public enum CapabilityRoutingDisposition {
    LEGACY,
    SHADOW,
    CANDIDATE_PIPELINE
}
