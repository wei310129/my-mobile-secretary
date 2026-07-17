package com.aproject.aidriven.mymobilesecretary.intent.application;

/** Request-scoped, in-memory bridge for model metadata without leaking it into domain commands. */
final class IntentInterpreterTelemetryContext {

    private static final ThreadLocal<Collector> CURRENT = new ThreadLocal<>();

    private IntentInterpreterTelemetryContext() {
    }

    static Scope open() {
        Collector previous = CURRENT.get();
        Collector collector = new Collector();
        CURRENT.set(collector);
        return new Scope(previous, collector);
    }

    static void record(Telemetry telemetry) {
        Collector collector = CURRENT.get();
        if (collector != null) {
            collector.telemetry = telemetry;
        }
    }

    static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    record Telemetry(String model, Integer inputTokens, Integer outputTokens,
                     Long modelLatencyMs, Long parsingLatencyMs) {
    }

    static final class Scope implements AutoCloseable {

        private final Collector previous;
        private final Collector current;
        private boolean closed;

        private Scope(Collector previous, Collector current) {
            this.previous = previous;
            this.current = current;
        }

        Telemetry snapshot() {
            return current.telemetry;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static final class Collector {
        private Telemetry telemetry;
    }
}
