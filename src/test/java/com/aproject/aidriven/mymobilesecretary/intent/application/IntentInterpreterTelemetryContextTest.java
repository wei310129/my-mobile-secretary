package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentInterpreterTelemetryContextTest {

    @Test
    void capturesModelUsageOnlyInsideTheCurrentRequestScope() {
        IntentInterpreterTelemetryContext.Telemetry telemetry =
                new IntentInterpreterTelemetryContext.Telemetry(
                        "claude-sonnet", 120, 35, 450L, 2L);

        try (IntentInterpreterTelemetryContext.Scope scope =
                     IntentInterpreterTelemetryContext.open()) {
            IntentInterpreterTelemetryContext.record(telemetry);
            assertThat(scope.snapshot()).isEqualTo(telemetry);
        }

        try (IntentInterpreterTelemetryContext.Scope next =
                     IntentInterpreterTelemetryContext.open()) {
            assertThat(next.snapshot()).isNull();
        }
    }
}
