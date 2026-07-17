package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDomain;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityId;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Safe rollout controls for candidate routing; defaults never replace the legacy path. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.intent.capability-routing")
public class CapabilityRoutingProperties {

    public enum Mode {
        LEGACY,
        SHADOW,
        ACTIVE
    }

    private Mode mode = Mode.SHADOW;
    private Set<CapabilityDomain> domains = Set.of();
    private Set<String> capabilities = Set.of();

    @Min(1)
    @Max(12)
    private int maxCandidates = 12;

    @Positive
    private int legacyPromptCharacters = 14_500;

    @DecimalMin(value = "0.1", inclusive = true)
    private double estimatedCharactersPerToken = 2.0d;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Set<CapabilityDomain> getDomains() {
        return domains;
    }

    public void setDomains(Set<CapabilityDomain> domains) {
        this.domains = domains == null ? Set.of() : Set.copyOf(domains);
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Set<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            this.capabilities = Set.of();
            return;
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String value : capabilities) {
            validated.add(CapabilityId.of(Objects.requireNonNull(value, "capability allowlist entry")).value());
        }
        this.capabilities = Set.copyOf(validated);
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getLegacyPromptCharacters() {
        return legacyPromptCharacters;
    }

    public void setLegacyPromptCharacters(int legacyPromptCharacters) {
        this.legacyPromptCharacters = legacyPromptCharacters;
    }

    public double getEstimatedCharactersPerToken() {
        return estimatedCharactersPerToken;
    }

    public void setEstimatedCharactersPerToken(double estimatedCharactersPerToken) {
        this.estimatedCharactersPerToken = estimatedCharactersPerToken;
    }

    /** Empty allowlists mean all capabilities in SHADOW and none in ACTIVE. */
    public boolean isAllowed(CapabilityDescriptor descriptor) {
        return domains.contains(descriptor.domain()) || capabilities.contains(descriptor.id().value());
    }

    public boolean hasAllowlist() {
        return !domains.isEmpty() || !capabilities.isEmpty();
    }
}
