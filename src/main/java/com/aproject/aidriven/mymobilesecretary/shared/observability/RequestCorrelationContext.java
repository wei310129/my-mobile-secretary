package com.aproject.aidriven.mymobilesecretary.shared.observability;

import java.util.UUID;
import java.util.function.Supplier;

/** Request correlation that also works for background jobs without leaking across virtual threads. */
public final class RequestCorrelationContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private RequestCorrelationContext() {
    }

    public static UUID currentId() {
        UUID current = CURRENT.get();
        return current == null ? UUID.randomUUID() : current;
    }

    public static Scope open(UUID requestId) {
        UUID previous = CURRENT.get();
        CURRENT.set(requestId == null ? UUID.randomUUID() : requestId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static <T> T run(UUID requestId, Supplier<T> action) {
        try (Scope ignored = open(requestId)) {
            return action.get();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
