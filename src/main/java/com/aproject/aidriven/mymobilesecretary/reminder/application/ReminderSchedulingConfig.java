package com.aproject.aidriven.mymobilesecretary.reminder.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 啟用背景排程(延遲提醒佇列輪詢)。
 * 測試環境以 app.scheduling.enabled=false 關閉,worker 由測試直接呼叫,時序才可控。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSchedulingConfig {
}
