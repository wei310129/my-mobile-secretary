package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 可行性引擎設定(簡化版參數;TDX 路線時間接上後粗估就退役)。
 *
 * 保守原則:寧可誤報要求確認,不可漏報放行。
 *
 * @param assumedSpeedKmh 直線距離換算交通時間的假設速度。25 km/h 已含
 *                        市區繞路折扣(直線距離比實際路線短 ~30%)
 * @param transferBuffer  每段移動的固定緩衝(出門、停車、等車)
 */
@ConfigurationProperties(prefix = "app.feasibility")
public record FeasibilityProperties(
        @DefaultValue("25") double assumedSpeedKmh,
        @DefaultValue("10m") Duration transferBuffer
) {
}
