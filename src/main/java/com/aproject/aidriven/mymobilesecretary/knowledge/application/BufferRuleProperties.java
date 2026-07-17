package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 緩衝規則設定。
 *
 * @param minSamples 樣本數達門檻才開始建議緩衝(太少樣本 = 巧合,不是習慣)
 * @param maxBuffer  建議緩衝上限(單次極端超時不該把之後每次驗算都撐爆)
 */
@ConfigurationProperties(prefix = "app.buffer")
public record BufferRuleProperties(
        @DefaultValue("3") int minSamples,
        @DefaultValue("2h") Duration maxBuffer
) {
}
