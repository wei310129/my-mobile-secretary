package com.aproject.aidriven.mymobilesecretary.intent.capability;

/** Safe boundary exception for unknown, malformed or invalid capability arguments. */
public final class CapabilityArgumentException extends IllegalArgumentException {

    private final CapabilityId capabilityId;
    private final int capabilityVersion;

    public CapabilityArgumentException(CapabilityId capabilityId, int capabilityVersion, String message) {
        super(message);
        this.capabilityId = capabilityId;
        this.capabilityVersion = capabilityVersion;
    }

    public CapabilityArgumentException(
            CapabilityId capabilityId, int capabilityVersion, String message, Throwable cause) {
        super(message, cause);
        this.capabilityId = capabilityId;
        this.capabilityVersion = capabilityVersion;
    }

    public CapabilityId capabilityId() {
        return capabilityId;
    }

    public int capabilityVersion() {
        return capabilityVersion;
    }
}
