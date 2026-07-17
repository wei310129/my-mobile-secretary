package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.Objects;

/** Typed, validated arguments paired with the exact registered handler version. */
public record ValidatedCapabilityCall<P>(
        CapabilityDescriptor descriptor,
        CapabilityHandler<P> handler,
        P arguments) {

    public ValidatedCapabilityCall {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(arguments, "arguments");
    }
}
