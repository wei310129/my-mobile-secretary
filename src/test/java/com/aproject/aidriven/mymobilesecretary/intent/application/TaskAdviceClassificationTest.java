package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.handler.TaskQueryIntentHandler;
import org.junit.jupiter.api.Test;

class TaskAdviceClassificationTest {
    @Test
    void onlyClearlyLocationBasedTitlesAskForPlace() {
        assertThat(TaskQueryIntentHandler.looksLocationBased("拿包裹")).isTrue();
        assertThat(TaskQueryIntentHandler.looksLocationBased("買小兒子的奶粉")).isTrue();
        assertThat(TaskQueryIntentHandler.looksLocationBased("完成客戶簡報")).isFalse();
        assertThat(TaskQueryIntentHandler.looksLocationBased("線上回覆郵件")).isFalse();
    }
}
