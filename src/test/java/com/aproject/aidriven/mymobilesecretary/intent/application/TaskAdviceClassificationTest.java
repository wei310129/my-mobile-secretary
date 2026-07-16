package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskAdviceClassificationTest {
    @Test
    void onlyClearlyLocationBasedTitlesAskForPlace() {
        assertThat(IntentService.looksLocationBased("拿包裹")).isTrue();
        assertThat(IntentService.looksLocationBased("買小兒子的奶粉")).isTrue();
        assertThat(IntentService.looksLocationBased("完成客戶簡報")).isFalse();
        assertThat(IntentService.looksLocationBased("線上回覆郵件")).isFalse();
    }
}
