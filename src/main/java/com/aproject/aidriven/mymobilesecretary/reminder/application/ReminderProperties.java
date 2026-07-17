package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 提醒引擎設定(數值定案紀錄見 development-plan.md §1)。
 *
 * @param debounceWindow     同一任務兩次提醒的最小間隔,預設 10 分鐘
 * @param escalationInterval 提醒後未確認,多久後升級催促,預設 15 分鐘
 * @param maxEscalations     最多催促次數,預設 3 次(之後不再輪,避免變騷擾)
 */
@ConfigurationProperties(prefix = "app.reminder")
public record ReminderProperties(
        @DefaultValue("10m") Duration debounceWindow,
        @DefaultValue("15m") Duration escalationInterval,
        @DefaultValue("3") int maxEscalations
) {
}
