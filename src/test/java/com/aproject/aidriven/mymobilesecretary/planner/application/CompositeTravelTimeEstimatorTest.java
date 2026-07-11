package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxProperties;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 交通估算 fallback 測試:TDX 可用就用、失敗退直線、關閉直接直線。
 * 這是「外部 API 失敗不拖垮核心」原則在可行性引擎的落點。
 */
@ExtendWith(MockitoExtension.class)
class CompositeTravelTimeEstimatorTest {

    private static final Instant DEPART = Instant.parse("2026-07-11T08:00:00Z");
    private static final FeasibilityProperties FEASIBILITY =
            new FeasibilityProperties(25, Duration.ofMinutes(10));

    @Mock
    private TdxRoutingClient tdxRoutingClient;

    private final StraightLineTravelTimeEstimator fallback = new StraightLineTravelTimeEstimator(FEASIBILITY);

    private CompositeTravelTimeEstimator estimator(boolean enabled) {
        TdxProperties props = new TdxProperties(enabled, "http://x", "http://x/token",
                enabled ? "cid" : "", enabled ? "secret" : "", Duration.ofSeconds(1));
        return new CompositeTravelTimeEstimator(tdxRoutingClient, props, fallback, FEASIBILITY);
    }

    @Test
    void usesTdxTransitTimePlusBufferWhenAvailable() {
        when(tdxRoutingClient.getTransitTravelTime(any())).thenReturn(Duration.ofMinutes(45));

        Duration result = estimator(true).estimate(25.0, 121.5, 24.9, 121.5, DEPART);

        // 45 分鐘車程 + 10 分鐘轉場緩衝
        assertThat(result).isEqualTo(Duration.ofMinutes(55));
    }

    @Test
    void fallsBackToStraightLineWhenTdxFails() {
        when(tdxRoutingClient.getTransitTravelTime(any()))
                .thenThrow(new IntegrationException("TDX down"));

        Duration result = estimator(true).estimate(25.0, 121.5, 24.9, 121.5, DEPART);

        assertThat(result).isEqualTo(fallback.estimate(25.0, 121.5, 24.9, 121.5, DEPART));
    }

    @Test
    void disabledTdxGoesStraightToFallbackWithoutCalling() {
        Duration result = estimator(false).estimate(25.0, 121.5, 24.9, 121.5, DEPART);

        assertThat(result).isEqualTo(fallback.estimate(25.0, 121.5, 24.9, 121.5, DEPART));
        verify(tdxRoutingClient, never()).getTransitTravelTime(any());
    }
}
