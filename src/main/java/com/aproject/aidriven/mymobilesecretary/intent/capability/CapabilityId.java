package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, version-independent identifier used in prompts and decision traces. */
public record CapabilityId(String value) implements Comparable<CapabilityId> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_LENGTH = 100;

    public CapabilityId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "capability id must be 1-100 lowercase characters separated by '.', '_' or '-': " + value);
        }
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }

    @Override
    public int compareTo(CapabilityId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
