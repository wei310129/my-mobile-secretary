package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CandidateResolver;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDomain;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityHandler;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityId;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityPromptAssembler;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRegistry;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRisk;
import com.aproject.aidriven.mymobilesecretary.intent.capability.ContextRequirement;
import com.aproject.aidriven.mymobilesecretary.intent.capability.WeightedKeywordCandidateResolver;
import com.aproject.aidriven.mymobilesecretary.intent.capability.core.CoreCapabilityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class CapabilityShadowRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final CoreCapabilityConfiguration core = new CoreCapabilityConfiguration();

    @Test
    void defaultsToSideEffectFreeShadowWithPromptContextVersionsAndSavings() {
        CapabilityRegistry registry = coreRegistry();
        CapabilityRoutingProperties properties = new CapabilityRoutingProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        CapabilityShadowRouter router = router(
                new WeightedKeywordCandidateResolver(registry), registry, properties, metrics);

        CapabilityShadowRoute route = router.route("明天下午兩點到三點開會");

        assertThat(route.disposition()).isEqualTo(CapabilityRoutingDisposition.SHADOW);
        assertThat(route.fallbackReason()).isEqualTo(CapabilityFallbackReason.NONE);
        assertThat(route.shadowOnly()).isTrue();
        assertThat(route.candidates().getFirst().descriptor().id().value())
                .isEqualTo("schedule.create");
        assertThat(route.prompt().promptVersion()).isEqualTo("capability-candidates/v1");
        assertThat(route.prompt().promptHash()).matches("[0-9a-f]{64}");
        assertThat(route.schemaVersions()).containsEntry("schedule.create", 1);
        assertThat(route.contextPlan().requirements()).contains(
                ContextRequirement.SCHEDULE_WINDOW,
                ContextRequirement.PLACES);
        assertThat(route.savings().available()).isTrue();
        assertThat(route.savings().candidateCharacters()).isEqualTo(route.prompt().content().length());
        assertThat(route.savings().estimatedCharacterSavings()).isPositive();
        assertThat(route.savings().estimatedTokenSavings()).isPositive();
        assertThat(metrics.get(CapabilityShadowRouter.DECISION_COUNTER)
                .tag("disposition", "shadow").tag("reason", "none")
                .counter().count()).isEqualTo(1.0d);
        assertThat(metrics.get(CapabilityShadowRouter.ROUTING_TIMER).timer().count()).isEqualTo(1L);
    }

    @Test
    void activeModeRequiresAllowlistAndSupportsCapabilityOrDomainRollout() {
        CapabilityRegistry registry = coreRegistry();
        CapabilityRoutingProperties properties = new CapabilityRoutingProperties();
        properties.setMode(CapabilityRoutingProperties.Mode.ACTIVE);
        CapabilityShadowRouter router = router(
                new WeightedKeywordCandidateResolver(registry), registry, properties, null);

        CapabilityShadowRoute noAllowlist = router.route("新增待辦買牛奶");
        assertThat(noAllowlist.disposition()).isEqualTo(CapabilityRoutingDisposition.LEGACY);
        assertThat(noAllowlist.fallbackReason()).isEqualTo(CapabilityFallbackReason.OUTSIDE_ALLOWLIST);

        properties.setCapabilities(Set.of("task.create"));
        CapabilityShadowRoute capabilityRollout = router.route("新增待辦買牛奶");
        assertThat(capabilityRollout.disposition())
                .isEqualTo(CapabilityRoutingDisposition.CANDIDATE_PIPELINE);
        assertThat(capabilityRollout.candidates())
                .extracting(candidate -> candidate.descriptor().id().value())
                .containsExactly("task.create");

        properties.setCapabilities(Set.of());
        properties.setDomains(Set.of(CapabilityDomain.SCHEDULE));
        CapabilityShadowRoute domainRollout = router.route("昨天有什麼行程");
        assertThat(domainRollout.disposition())
                .isEqualTo(CapabilityRoutingDisposition.CANDIDATE_PIPELINE);
        assertThat(domainRollout.candidates())
                .allMatch(candidate -> candidate.descriptor().domain() == CapabilityDomain.SCHEDULE);
        assertThat(domainRollout.prompt().content()).doesNotContain("task.create", "inventory.set");
    }

    @Test
    void tiedTopCandidateAcrossRolloutBoundaryFailsOpenToLegacy() {
        CapabilityHandler<TestPayload> allowed = handler(
                "task.alpha", CapabilityRisk.QUERY, "共同", ignored -> { });
        CapabilityHandler<TestPayload> outside = handler(
                "price.beta", CapabilityRisk.QUERY, "共同", ignored -> { });
        CapabilityRegistry registry = registry(List.of(allowed, outside));
        CapabilityRoutingProperties properties = new CapabilityRoutingProperties();
        properties.setMode(CapabilityRoutingProperties.Mode.ACTIVE);
        properties.setCapabilities(Set.of("task.alpha"));

        CapabilityShadowRoute route = router(
                new WeightedKeywordCandidateResolver(registry), registry, properties, null)
                .route("共同");

        assertThat(route.disposition()).isEqualTo(CapabilityRoutingDisposition.LEGACY);
        assertThat(route.fallbackReason())
                .isEqualTo(CapabilityFallbackReason.AMBIGUOUS_ROLLOUT_BOUNDARY);
        assertThat(route.prompt()).isNull();
    }

    @Test
    void shadowNeverInvokesMutationHandlerValidationOrExecution() {
        AtomicInteger validations = new AtomicInteger();
        CapabilityHandler<TestPayload> mutation = handler(
                "inventory.test_adjust",
                CapabilityRisk.MUTATION,
                "測試增減庫存",
                ignored -> validations.incrementAndGet());
        CapabilityRegistry registry = registry(List.of(mutation));

        CapabilityShadowRoute route = router(
                new WeightedKeywordCandidateResolver(registry),
                registry,
                new CapabilityRoutingProperties(),
                null).route("測試增減庫存");

        assertThat(route.disposition()).isEqualTo(CapabilityRoutingDisposition.SHADOW);
        assertThat(route.candidates().getFirst().descriptor().risk())
                .isEqualTo(CapabilityRisk.MUTATION);
        assertThat(validations).hasValue(0);
    }

    @Test
    void legacyModeNoMatchAndResolverFailureAllFailOpenWithoutPrompt() {
        CapabilityRegistry registry = coreRegistry();
        AtomicInteger resolverCalls = new AtomicInteger();
        CandidateResolver countingResolver = (message, limit) -> {
            resolverCalls.incrementAndGet();
            return List.of();
        };
        CapabilityRoutingProperties legacyProperties = new CapabilityRoutingProperties();
        legacyProperties.setMode(CapabilityRoutingProperties.Mode.LEGACY);

        CapabilityShadowRoute legacy = router(
                countingResolver, registry, legacyProperties, null).route("任何文字");
        assertThat(legacy.fallbackReason()).isEqualTo(CapabilityFallbackReason.MODE_LEGACY);
        assertThat(legacy.usesLegacy()).isTrue();
        assertThat(legacy.prompt()).isNull();
        assertThat(legacy.savings().available()).isFalse();
        assertThat(resolverCalls).hasValue(0);

        CapabilityShadowRoute noMatch = router(
                new WeightedKeywordCandidateResolver(registry),
                registry,
                new CapabilityRoutingProperties(),
                null).route("完全沒有相關能力的字串");
        assertThat(noMatch.fallbackReason()).isEqualTo(CapabilityFallbackReason.NO_CANDIDATES);

        CandidateResolver failingResolver = (message, limit) -> {
            throw new IllegalStateException("simulated local resolver failure");
        };
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        CapabilityShadowRoute failed = router(
                failingResolver, registry, new CapabilityRoutingProperties(), metrics)
                .route("不應拋出例外");
        assertThat(failed.fallbackReason()).isEqualTo(CapabilityFallbackReason.ROUTING_ERROR);
        assertThat(failed.usesLegacy()).isTrue();
        assertThat(metrics.get(CapabilityShadowRouter.DECISION_COUNTER)
                .tag("disposition", "legacy").tag("reason", "routing_error")
                .counter().count()).isEqualTo(1.0d);
    }

    private CapabilityShadowRouter router(
            CandidateResolver resolver,
            CapabilityRegistry registry,
            CapabilityRoutingProperties properties,
            SimpleMeterRegistry metrics) {
        return new CapabilityShadowRouter(
                resolver,
                new CapabilityPromptAssembler(objectMapper),
                registry,
                properties,
                Optional.ofNullable(metrics));
    }

    private CapabilityRegistry coreRegistry() {
        return registry(List.of(
                core.createTaskCapability(),
                core.createScheduleCapability(),
                core.askTaskInfoCapability(),
                core.askPriceHistoryCapability(),
                core.cancelTaskCapability(),
                core.removeTaskPlaceCapability(),
                core.setInventoryCapability(),
                core.adjustInventoryCapability(),
                core.listSchedulesOnDateCapability(),
                core.explainLastFailureCapability()));
    }

    private CapabilityRegistry registry(List<CapabilityHandler<?>> handlers) {
        return new CapabilityRegistry(handlers, objectMapper, validator);
    }

    private static CapabilityHandler<TestPayload> handler(
            String id,
            CapabilityRisk risk,
            String phrase,
            java.util.function.Consumer<TestPayload> validation) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                CapabilityId.of(id),
                1,
                CapabilityDomain.SYSTEM,
                risk,
                TestPayload.class,
                "Test routing capability",
                Set.of(),
                List.of(phrase),
                Set.of(phrase));
        return new CapabilityHandler<>() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Class<TestPayload> inputType() {
                return TestPayload.class;
            }

            @Override
            public void validate(TestPayload arguments) {
                validation.accept(arguments);
            }
        };
    }

    private record TestPayload(String value) {
    }
}
