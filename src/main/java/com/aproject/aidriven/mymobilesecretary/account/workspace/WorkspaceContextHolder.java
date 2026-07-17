package com.aproject.aidriven.mymobilesecretary.account.workspace;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Thread-confined workspace scope for HTTP, LINE and scheduled work.
 *
 * <p>The holder deliberately does not inherit values into child/executor threads. Background
 * work must open its own scope so an actor or tenant can never leak through a pooled thread.</p>
 */
public final class WorkspaceContextHolder {

    private static final ThreadLocal<WorkspaceContext> CURRENT = new ThreadLocal<>();
    private static volatile boolean legacyFallbackEnabled;

    private WorkspaceContextHolder() {
    }

    /** Returns only an explicitly opened context; it never synthesizes the local fallback. */
    public static Optional<WorkspaceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Resolves the explicit context or the deliberately enabled local/test compatibility tenant.
     * Production defaults to fail-closed when an entry point forgot to establish a scope.
     */
    public static WorkspaceContext requireContext() {
        WorkspaceContext current = CURRENT.get();
        if (current != null) {
            return current;
        }
        if (legacyFallbackEnabled) {
            return new WorkspaceContext(LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID,
                    WorkspaceChannel.LOCAL);
        }
        throw new IllegalStateException("Workspace context is required");
    }

    /** Opens a nestable scope. Always use this with try-with-resources. */
    public static Scope open(WorkspaceContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        WorkspaceContext previous = CURRENT.get();
        CURRENT.set(context);
        return new Scope(Thread.currentThread(), previous);
    }

    public static Scope openAuthentication() {
        return open(WorkspaceContext.authentication());
    }

    public static void runWith(WorkspaceContext context, Runnable action) {
        try (Scope ignored = open(context)) {
            action.run();
        }
    }

    public static <T> T callWith(WorkspaceContext context, Callable<T> action) throws Exception {
        try (Scope ignored = open(context)) {
            return action.call();
        }
    }

    /** Emergency cleanup for framework boundaries; normal code should close Scope instead. */
    public static void clear() {
        CURRENT.remove();
    }

    static void configureLegacyFallback(boolean enabled) {
        legacyFallbackEnabled = enabled;
    }

    static boolean isLegacyFallbackEnabled() {
        return legacyFallbackEnabled;
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner;
        private final WorkspaceContext previous;
        private boolean closed;

        private Scope(Thread owner, WorkspaceContext previous) {
            this.owner = owner;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Workspace scope must be closed by its owner thread");
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
