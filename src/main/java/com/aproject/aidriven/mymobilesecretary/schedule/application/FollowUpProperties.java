package com.aproject.aidriven.mymobilesecretary.schedule.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 行程結果追蹤詢問設定(數值為 2026-07-14 使用者拍板,變更前必須先問使用者)。
 *
 * @param afterEnd         行程結束後多久發詢問(時間路徑)
 * @param afterExit        GPS 離開行程地點後多久發詢問(較早者先發)
 * @param dailyLimit       一天最多發幾則追蹤詢問(台北時間換日重算)
 * @param lookback         只追蹤這段時間內結束的行程(避免部署後舊行程湧入詢問)
 * @param exitRadiusMeters GPS 離開點與行程地點多近才算「離開該行程的地點」
 */
@ConfigurationProperties(prefix = "app.follow-up")
public record FollowUpProperties(
        @DefaultValue("15m") Duration afterEnd,
        @DefaultValue("5m") Duration afterExit,
        @DefaultValue("50") int dailyLimit,
        @DefaultValue("24h") Duration lookback,
        @DefaultValue("200") double exitRadiusMeters
) {
}
