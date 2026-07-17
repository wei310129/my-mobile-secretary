package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.intent.capability.ContextRequirement;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityPromptSavings;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRoute;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRouter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Privacy-bounded projection of a local capability route for decision tracing only.
 *
 * <p>The observation deliberately excludes the user message, matched terms and prompt content.
 * It cannot authorize execution and always fails open to the authoritative legacy flow.</p>
 */
record CapabilityShadowObservation(
        boolean observed,
        String routerVersion,
        String disposition,
        String fallbackReason,
        Map<String, Double> candidateScores,
        String promptVersion,
        String promptHash,
        Map<String, Integer> tokenEstimate,
        List<String> contextPlan,
        long latencyMs) {

    static final String ROUTER_VERSION = "capability-shadow/v1";

    CapabilityShadowObservation {
        candidateScores = Map.copyOf(Objects.requireNonNull(candidateScores, "candidateScores"));
        tokenEstimate = Map.copyOf(Objects.requireNonNull(tokenEstimate, "tokenEstimate"));
        contextPlan = List.copyOf(Objects.requireNonNull(contextPlan, "contextPlan"));
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs cannot be negative");
        }
    }

    static CapabilityShadowObservation observe(CapabilityShadowRouter router, String userText) {
        if (router == null) {
            return notObserved();
        }
        long startedNanos = System.nanoTime();
        try {
            CapabilityShadowRoute route = Objects.requireNonNull(
                    router.route(userText), "shadow router returned null");
            return from(route, elapsedMillis(startedNanos));
        } catch (RuntimeException ignored) {
            return routingFailure(elapsedMillis(startedNanos));
        }
    }

    private static CapabilityShadowObservation from(CapabilityShadowRoute route, long latencyMs) {
        LinkedHashMap<String, Double> candidates = new LinkedHashMap<>();
        for (CapabilityCandidate candidate : route.candidates()) {
            candidates.put(candidate.descriptor().id().value(), (double) candidate.score());
        }

        String promptVersion = route.prompt() == null ? null : route.prompt().promptVersion();
        String promptHash = route.prompt() == null ? null : route.prompt().promptHash();
        List<String> contextPlan = route.contextPlan().requirements().stream()
                .map(ContextRequirement::key)
                .toList();

        return new CapabilityShadowObservation(
                true,
                ROUTER_VERSION,
                route.disposition().name(),
                route.fallbackReason().name(),
                candidates,
                promptVersion,
                promptHash,
                tokenEstimate(route.savings()),
                contextPlan,
                latencyMs);
    }

    private static Map<String, Integer> tokenEstimate(CapabilityPromptSavings savings) {
        if (!savings.available()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> estimate = new LinkedHashMap<>();
        estimate.put("legacyEstimatedTokens", savings.legacyEstimatedTokens());
        estimate.put("candidateEstimatedTokens", savings.candidateEstimatedTokens());
        estimate.put("estimatedTokenSavings", savings.estimatedTokenSavings());
        return estimate;
    }

    private static CapabilityShadowObservation routingFailure(long latencyMs) {
        return new CapabilityShadowObservation(
                true,
                ROUTER_VERSION,
                "LEGACY",
                "ROUTING_ERROR",
                Map.of(),
                null,
                null,
                Map.of(),
                List.of(),
                latencyMs);
    }

    private static CapabilityShadowObservation notObserved() {
        return new CapabilityShadowObservation(
                false, null, null, null, Map.of(), null, null, Map.of(), List.of(), 0L);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
}
