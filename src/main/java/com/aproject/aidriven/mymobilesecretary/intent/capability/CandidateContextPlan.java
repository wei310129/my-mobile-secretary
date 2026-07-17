package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Minimal, deterministic union of context fragments required by candidate capabilities. */
public record CandidateContextPlan(Set<ContextRequirement> requirements) {

    public CandidateContextPlan {
        Objects.requireNonNull(requirements, "requirements");
        if (requirements.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("requirements must not contain null");
        }
        requirements = Collections.unmodifiableNavigableSet(new TreeSet<>(requirements));
    }

    public static CandidateContextPlan from(Collection<CapabilityDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        TreeSet<ContextRequirement> requirements = new TreeSet<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptors must not contain null");
            }
            requirements.addAll(descriptor.contextRequirements());
        }
        return new CandidateContextPlan(requirements);
    }

    public boolean requires(ContextRequirement requirement) {
        return requirements.contains(Objects.requireNonNull(requirement, "requirement"));
    }
}
