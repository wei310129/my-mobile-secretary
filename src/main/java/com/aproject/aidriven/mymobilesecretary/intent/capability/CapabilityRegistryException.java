package com.aproject.aidriven.mymobilesecretary.intent.capability;

/** Signals invalid capability registration discovered during application startup. */
public final class CapabilityRegistryException extends IllegalStateException {

    public CapabilityRegistryException(String message) {
        super(message);
    }
}
