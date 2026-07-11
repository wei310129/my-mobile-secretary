package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeoDistance;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 直線距離粗估(保底方案):直線距離 ÷ 假設速度 + 固定緩衝。
 * TDX 不可用或失敗時的 fallback——外部 API 掛掉,可行性把關照樣運作。
 */
@Component
public class StraightLineTravelTimeEstimator implements TravelTimeEstimator {

    private final FeasibilityProperties properties;

    public StraightLineTravelTimeEstimator(FeasibilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Duration estimate(double fromLat, double fromLon, double toLat, double toLon, Instant departAt) {
        double meters = GeoDistance.metersBetween(fromLat, fromLon, toLat, toLon);
        // 保守估計(25 km/h 已含繞路折扣),寧可誤報要求確認
        long seconds = Math.round(meters / (properties.assumedSpeedKmh() * 1000.0 / 3600.0));
        return Duration.ofSeconds(seconds).plus(properties.transferBuffer());
    }
}
