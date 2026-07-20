package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CandidateContextPlan;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CandidateResolver;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityPromptAssembler;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityPromptAssembly;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Produces a shadow/rollout routing artifact without calling a model or executing a capability.
 * Any uncertainty or local failure returns the legacy path.
 */
@Component
public final class CapabilityShadowRouter {

    static final String DECISION_COUNTER = "intent.capability.routing.decisions";
    static final String ROUTING_TIMER = "intent.capability.routing.duration";

    private final CandidateResolver candidateResolver;
    private final CapabilityPromptAssembler promptAssembler;
    private final CapabilityRegistry registry;
    private final CapabilityRoutingProperties properties;
    private final MeterRegistry meterRegistry;

    public CapabilityShadowRouter(
            CandidateResolver candidateResolver,
            CapabilityPromptAssembler promptAssembler,
            CapabilityRegistry registry,
            CapabilityRoutingProperties properties,
            Optional<MeterRegistry> meterRegistry) {
        this.candidateResolver = candidateResolver;
        this.promptAssembler = promptAssembler;
        this.registry = registry;
        this.properties = properties;
        this.meterRegistry = meterRegistry.orElse(null);
    }

    public CapabilityShadowRoute route(String message) {
        long startedAt = System.nanoTime();
        CapabilityShadowRoute result;
        try {
            result = routeSafely(message);
        } catch (RuntimeException exception) {
            result = legacy(CapabilityFallbackReason.ROUTING_ERROR);
        }
        try {
            recordMetrics(result, System.nanoTime() - startedAt);
        } catch (RuntimeException ignored) {
            // Metrics must never break routing or prevent the legacy fallback.
        }
        return result;
    }

    private CapabilityShadowRoute routeSafely(String message) {
        if (properties.getMode() == CapabilityRoutingProperties.Mode.LEGACY) {
            return legacy(CapabilityFallbackReason.MODE_LEGACY);
        }

        List<CapabilityCandidate> resolved = candidateResolver.resolve(
                message, properties.getMaxCandidates());
        if (resolved.isEmpty()) {
            return legacy(CapabilityFallbackReason.NO_CANDIDATES);
        }
        resolved = resolved.stream()
                .filter(candidate -> candidate.score() >= properties.getMinimumCandidateScore())
                .toList();
        if (resolved.isEmpty()) {
            return legacy(CapabilityFallbackReason.BELOW_RELEVANCE_THRESHOLD);
        }
        verifyRegistryBacked(resolved);

        List<CapabilityCandidate> scoped;
        CapabilityRoutingDisposition disposition;
        if (properties.getMode() == CapabilityRoutingProperties.Mode.SHADOW) {
            scoped = properties.hasAllowlist()
                    ? resolved.stream().filter(this::allowed).toList()
                    : resolved;
            if (scoped.isEmpty()) {
                return legacy(CapabilityFallbackReason.OUTSIDE_ALLOWLIST);
            }
            disposition = CapabilityRoutingDisposition.SHADOW;
        } else {
            if (!properties.hasAllowlist()) {
                return legacy(CapabilityFallbackReason.OUTSIDE_ALLOWLIST);
            }
            long strongestScore = resolved.getFirst().score();
            List<CapabilityCandidate> strongestCandidates = resolved.stream()
                    .takeWhile(candidate -> candidate.score() == strongestScore)
                    .toList();
            boolean strongestIncludesAllowed = strongestCandidates.stream().anyMatch(this::allowed);
            boolean strongestIncludesDisallowed = strongestCandidates.stream()
                    .anyMatch(candidate -> !allowed(candidate));
            if (strongestIncludesAllowed && strongestIncludesDisallowed) {
                return legacy(CapabilityFallbackReason.AMBIGUOUS_ROLLOUT_BOUNDARY);
            }
            if (!strongestIncludesAllowed) {
                return legacy(CapabilityFallbackReason.OUTSIDE_ALLOWLIST);
            }
            scoped = resolved.stream().filter(this::allowed).toList();
            disposition = CapabilityRoutingDisposition.CANDIDATE_PIPELINE;
        }

        List<CapabilityDescriptor> descriptors = scoped.stream()
                .map(CapabilityCandidate::descriptor)
                .toList();
        CapabilityPromptAssembly prompt = promptAssembler.assemble(descriptors);
        CapabilityPromptSavings savings = CapabilityPromptSavings.estimate(
                properties.getLegacyPromptCharacters(),
                prompt.content().length(),
                properties.getEstimatedCharactersPerToken());
        return new CapabilityShadowRoute(
                disposition,
                CapabilityFallbackReason.NONE,
                scoped,
                prompt,
                schemaVersions(descriptors),
                CandidateContextPlan.from(descriptors),
                savings);
    }

    private boolean allowed(CapabilityCandidate candidate) {
        return properties.isAllowed(candidate.descriptor());
    }

    private void verifyRegistryBacked(List<CapabilityCandidate> candidates) {
        for (CapabilityCandidate candidate : candidates) {
            CapabilityDescriptor registered = registry.descriptor(
                            candidate.descriptor().id(), candidate.descriptor().version())
                    .orElseThrow(() -> new IllegalStateException("resolver returned an unregistered capability"));
            if (!registered.equals(candidate.descriptor())) {
                throw new IllegalStateException("resolver descriptor differs from the registry");
            }
        }
    }

    private CapabilityShadowRoute legacy(CapabilityFallbackReason reason) {
        return new CapabilityShadowRoute(
                CapabilityRoutingDisposition.LEGACY,
                reason,
                List.of(),
                null,
                Map.of(),
                CandidateContextPlan.from(List.of()),
                CapabilityPromptSavings.unavailable());
    }

    private static Map<String, Integer> schemaVersions(List<CapabilityDescriptor> descriptors) {
        LinkedHashMap<String, Integer> versions = new LinkedHashMap<>();
        descriptors.forEach(descriptor -> versions.put(
                descriptor.id().value(), descriptor.version()));
        return versions;
    }

    private void recordMetrics(CapabilityShadowRoute result, long elapsedNanos) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(DECISION_COUNTER)
                .tag("disposition", result.disposition().name().toLowerCase())
                .tag("reason", result.fallbackReason().name().toLowerCase())
                .register(meterRegistry)
                .increment();
        Timer.builder(ROUTING_TIMER)
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(elapsedNanos, 0)));
    }
}
