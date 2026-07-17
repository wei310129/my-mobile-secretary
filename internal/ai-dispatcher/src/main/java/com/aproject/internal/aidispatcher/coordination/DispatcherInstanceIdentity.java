package com.aproject.internal.aidispatcher.coordination;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class DispatcherInstanceIdentity {

    private final String value;

    public DispatcherInstanceIdentity() {
        this.value = UUID.randomUUID().toString();
    }

    DispatcherInstanceIdentity(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Dispatcher instance identity is required");
        }
        this.value = value.strip();
    }

    public String value() {
        return value;
    }
}
