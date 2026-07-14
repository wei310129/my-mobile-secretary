package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 緩衝統計正確性測試:準時樣本要稀釋平均,不能只記超時。
 */
class BufferRuleTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");

    @Test
    void averageIncludesOnTimeSamplesAsZero() {
        BufferRule rule = BufferRule.create(1L, NOW);
        rule.recordSample(30, NOW);
        rule.recordSample(20, NOW);
        rule.recordSample(0, NOW);

        // (30 + 20 + 0) / 3 = 16.67 → 17
        assertThat(rule.averageOverrunMinutes()).isEqualTo(17);
        assertThat(rule.getSampleCount()).isEqualTo(3);
        assertThat(rule.getOnTimeCount()).isEqualTo(1);
    }

    @Test
    void emptyRuleAveragesZero() {
        assertThat(BufferRule.create(1L, NOW).averageOverrunMinutes()).isZero();
    }

    /** 上游若傳負值(理論上不會),防禦性當 0,不能讓統計變負。 */
    @Test
    void negativeSampleIsTreatedAsZero() {
        BufferRule rule = BufferRule.create(1L, NOW);
        rule.recordSample(-10, NOW);

        assertThat(rule.getTotalOverrunMinutes()).isZero();
        assertThat(rule.getOnTimeCount()).isEqualTo(1);
    }
}
