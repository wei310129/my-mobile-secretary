package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable metadata used for candidate selection, prompt assembly and policy checks. */
public record CapabilityDescriptor(
        CapabilityId id,
        int version,
        CapabilityDomain domain,
        CapabilityRisk risk,
        Class<?> inputType,
        String description,
        Set<ContextRequirement> contextRequirements,
        List<String> phrases,
        Set<String> keywords) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("capability version must be positive");
        }
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(inputType, "inputType");
        if (inputType.isPrimitive() || inputType.isArray()) {
            throw new IllegalArgumentException("capability inputType must be an object type");
        }
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("capability description must not be blank");
        }

        contextRequirements = immutableSet(contextRequirements, "contextRequirements");
        phrases = immutableStrings(phrases, "phrases");
        keywords = immutableStringsSet(keywords, "keywords");
    }

    private static <T> Set<T> immutableSet(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return Set.copyOf(values);
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream()
                .map(value -> requireText(value, name))
                .distinct()
                .toList();
    }

    private static Set<String> immutableStringsSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(requireText(value, name));
        }
        return Set.copyOf(result);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " entry");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(name + " must not contain blank text");
        }
        return stripped;
    }
}
