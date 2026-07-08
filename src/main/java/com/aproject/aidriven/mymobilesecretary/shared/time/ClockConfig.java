package com.aproject.aidriven.mymobilesecretary.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供全專案共用的 Clock bean。
 *
 * 關鍵規則:任何需要「現在時間」的程式一律注入 Clock,
 * 不直接呼叫 Instant.now() / LocalDateTime.now(),測試才能固定時間。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
