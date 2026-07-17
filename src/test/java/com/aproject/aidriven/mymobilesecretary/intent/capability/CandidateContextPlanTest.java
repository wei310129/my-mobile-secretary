package com.aproject.aidriven.mymobilesecretary.intent.capability;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.capability.core.CoreCapabilityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateContextPlanTest {

    private final CoreCapabilityConfiguration capabilities = new CoreCapabilityConfiguration();

    @Test
    void mergesOnlyCandidateRequirementsInStableOrderWithoutDuplicates() {
        CandidateContextPlan plan = CandidateContextPlan.from(List.of(
                capabilities.createTaskCapability().descriptor(),
                capabilities.cancelTaskCapability().descriptor(),
                capabilities.createTaskCapability().descriptor()));

        assertThat(plan.requirements()).containsExactly(
                ContextRequirement.CONVERSATION_HISTORY,
                ContextRequirement.PLACES,
                ContextRequirement.OPEN_TASKS);
        assertThat(plan.requires(ContextRequirement.OPEN_TASKS)).isTrue();
        assertThat(plan.requires(ContextRequirement.PRICE_HISTORY)).isFalse();
        assertThat(plan.requirements()).doesNotContain(
                ContextRequirement.ITEMS,
                ContextRequirement.SCHEDULE_WINDOW,
                ContextRequirement.LAST_INTENT_FAILURE);
    }

    @Test
    void supportsNoContextCandidatesWithoutAddingDefaults() {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                CapabilityId.of("system.health"),
                1,
                CapabilityDomain.SYSTEM,
                CapabilityRisk.QUERY,
                EmptyPayload.class,
                "Health check",
                java.util.Set.of(),
                List.of("系統正常嗎"),
                java.util.Set.of("系統"));

        assertThat(CandidateContextPlan.from(List.of(descriptor)).requirements()).isEmpty();
    }

    private record EmptyPayload() {
    }
}
