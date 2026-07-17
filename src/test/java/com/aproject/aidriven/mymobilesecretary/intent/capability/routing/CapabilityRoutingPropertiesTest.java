package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class CapabilityRoutingPropertiesTest {

    @Test
    void defaultsToShadowAndValidatesBoundedEfficiencySettings() {
        CapabilityRoutingProperties properties = new CapabilityRoutingProperties();

        assertThat(properties.getMode()).isEqualTo(CapabilityRoutingProperties.Mode.SHADOW);
        assertThat(properties.getDomains()).isEmpty();
        assertThat(properties.getCapabilities()).isEmpty();
        assertThat(properties.getMaxCandidates()).isEqualTo(12);
        assertThat(properties.getLegacyPromptCharacters()).isEqualTo(14_500);

        properties.setMaxCandidates(13);
        properties.setLegacyPromptCharacters(0);
        properties.setEstimatedCharactersPerToken(0);
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder(
                        "maxCandidates",
                        "legacyPromptCharacters",
                        "estimatedCharactersPerToken");
    }

    @Test
    void sanitizesCapabilityAllowlistThroughStableCapabilityIds() {
        CapabilityRoutingProperties properties = new CapabilityRoutingProperties();
        properties.setCapabilities(Set.of("task.create", "schedule.create"));

        assertThat(properties.getCapabilities()).containsExactlyInAnyOrder(
                "task.create", "schedule.create");
    }
}
