package com.aproject.aidriven.mymobilesecretary.schedule.application;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * pending 空閒詢問設定。
 *
 * @param idleWindow  「空閒」定義:從現在起這段時間內沒有已確認行程
 * @param minInterval 兩次詢問的最小間隔(避免變成騷擾)
 * @param start       每天可詢問的開始時間(台北時間)
 * @param end         每天可詢問的結束時間(晚上不吵人)
 */
@ConfigurationProperties(prefix = "app.pending.prompt")
public record PendingPromptProperties(
        @DefaultValue("1h") Duration idleWindow,
        @DefaultValue("4h") Duration minInterval,
        @DefaultValue("08:00") LocalTime start,
        @DefaultValue("21:00") LocalTime end
) {
}
