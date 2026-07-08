package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 提醒引擎設定。
 *
 * @param debounceWindow 同一任務兩次提醒的最小間隔(debounce),
 *                       預設 10 分鐘——同地點短時間重複進出不重複轟炸
 */
@ConfigurationProperties(prefix = "app.reminder")
public record ReminderProperties(
        @DefaultValue("10m") Duration debounceWindow
) {
}
