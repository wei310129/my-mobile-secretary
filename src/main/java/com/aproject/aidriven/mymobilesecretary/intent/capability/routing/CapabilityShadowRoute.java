package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CandidateContextPlan;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityPromptAssembly;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure routing decision; it contains no handler invocation or execution authority. */
public record CapabilityShadowRoute(
        CapabilityRoutingDisposition disposition,
        CapabilityFallbackReason fallbackReason,
        List<CapabilityCandidate> candidates,
        CapabilityPromptAssembly prompt,
        Map<String, Integer> schemaVersions,
        CandidateContextPlan contextPlan,
        CapabilityPromptSavings savings) {

    public CapabilityShadowRoute {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(fallbackReason, "fallbackReason");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        schemaVersions = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(schemaVersions, "schemaVersions")));
        Objects.requireNonNull(contextPlan, "contextPlan");
        Objects.requireNonNull(savings, "savings");
        if (disposition == CapabilityRoutingDisposition.LEGACY && prompt != null) {
            throw new IllegalArgumentException("legacy route must not expose a candidate prompt");
        }
        if (disposition != CapabilityRoutingDisposition.LEGACY && prompt == null) {
            throw new IllegalArgumentException("candidate route requires a prompt");
        }
    }

    public boolean usesLegacy() {
        return disposition == CapabilityRoutingDisposition.LEGACY;
    }

    public boolean shadowOnly() {
        return disposition == CapabilityRoutingDisposition.SHADOW;
    }
}
