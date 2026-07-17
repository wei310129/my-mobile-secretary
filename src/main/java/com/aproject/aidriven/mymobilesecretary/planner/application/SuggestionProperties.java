package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 順路建議設定。「待會」= 未來 window(使用者 2026-07-15 拍板 3 小時)。
 *
 * @param window            「待會」的時間窗
 * @param maxDistanceMeters 幾公尺內的任務地點才算「順路」
 * @param limit             最多建議幾件
 */
@ConfigurationProperties(prefix = "app.suggestion")
public record SuggestionProperties(
        @DefaultValue("3h") Duration window,
        @DefaultValue("2000") double maxDistanceMeters,
        @DefaultValue("5") int limit
) {
}
