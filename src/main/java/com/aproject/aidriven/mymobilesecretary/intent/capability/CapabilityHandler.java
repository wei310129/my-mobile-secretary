package com.aproject.aidriven.mymobilesecretary.intent.capability;

/**
 * Supplies one capability's metadata, input type and domain validation.
 *
 * <p>Execution is deliberately not part of this foundation interface. A later execution adapter
 * must require an authenticated actor and workspace context before invoking side effects.</p>
 */
public interface CapabilityHandler<P> {

    CapabilityDescriptor descriptor();

    Class<P> inputType();

    default void validate(P arguments) {
        // Optional domain validation after JSON and Jakarta Bean Validation have succeeded.
    }
}
