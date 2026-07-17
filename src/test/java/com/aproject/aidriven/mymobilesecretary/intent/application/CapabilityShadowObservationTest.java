package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CandidateContextPlan;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDomain;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityId;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityPromptAssembly;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRisk;
import com.aproject.aidriven.mymobilesecretary.intent.capability.ContextRequirement;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityFallbackReason;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityPromptSavings;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityRoutingDisposition;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRoute;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRouter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityShadowObservationTest {

    @Test
    void capturesStructuredMetadataWithoutRawTextPromptOrMatchedTermsAndRoutesOnce() {
        String userText = "private user message";
        CapabilityDescriptor descriptor = descriptor();
        CapabilityShadowRoute route = new CapabilityShadowRoute(
                CapabilityRoutingDisposition.SHADOW,
                CapabilityFallbackReason.NONE,
                List.of(new CapabilityCandidate(descriptor, 12L, List.of("sensitive-match"))),
                new CapabilityPromptAssembly(
                        "capability-candidates/v1", "a".repeat(64), "private prompt body"),
                Map.of("task.create", 1),
                CandidateContextPlan.from(List.of(descriptor)),
                new CapabilityPromptSavings(true, 1_000, 200, 800, 250, 50, 200));
        CapabilityShadowRouter router = mock(CapabilityShadowRouter.class);
        when(router.route(userText)).thenReturn(route);

        CapabilityShadowObservation observation =
                CapabilityShadowObservation.observe(router, userText);

        assertThat(observation.observed()).isTrue();
        assertThat(observation.routerVersion()).isEqualTo("capability-shadow/v1");
        assertThat(observation.disposition()).isEqualTo("SHADOW");
        assertThat(observation.fallbackReason()).isEqualTo("NONE");
        assertThat(observation.candidateScores()).containsEntry("task.create", 12.0).hasSize(1);
        assertThat(observation.promptVersion()).isEqualTo("capability-candidates/v1");
        assertThat(observation.promptHash()).isEqualTo("a".repeat(64));
        assertThat(observation.tokenEstimate())
                .containsAllEntriesOf(Map.of(
                        "legacyEstimatedTokens", 250,
                        "candidateEstimatedTokens", 50,
                        "estimatedTokenSavings", 200))
                .hasSize(3);
        assertThat(observation.contextPlan()).containsExactly("task.open");
        assertThat(observation.toString())
                .doesNotContain(userText, "sensitive-match", "private prompt body");
        verify(router, times(1)).route(userText);
        verifyNoMoreInteractions(router);
    }

    @Test
    void routerFailureFailsOpenToTraceableLegacyDisposition() {
        String userText = "must never reach trace metadata";
        CapabilityShadowRouter router = mock(CapabilityShadowRouter.class);
        when(router.route(userText)).thenThrow(new IllegalStateException("local routing failed"));

        CapabilityShadowObservation observation =
                CapabilityShadowObservation.observe(router, userText);

        assertThat(observation.observed()).isTrue();
        assertThat(observation.disposition()).isEqualTo("LEGACY");
        assertThat(observation.fallbackReason()).isEqualTo("ROUTING_ERROR");
        assertThat(observation.candidateScores()).isEmpty();
        assertThat(observation.promptVersion()).isNull();
        assertThat(observation.promptHash()).isNull();
        assertThat(observation.tokenEstimate()).isEmpty();
        assertThat(observation.contextPlan()).isEmpty();
        assertThat(observation.toString()).doesNotContain(userText, "local routing failed");
        verify(router, times(1)).route(userText);
        verifyNoMoreInteractions(router);
    }

    private static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CapabilityId.of("task.create"),
                1,
                CapabilityDomain.TASK,
                CapabilityRisk.MUTATION,
                TestPayload.class,
                "Create a task",
                Set.of(ContextRequirement.OPEN_TASKS),
                List.of("新增待辦"),
                Set.of("待辦"));
    }

    private record TestPayload(String title) {
    }
}
