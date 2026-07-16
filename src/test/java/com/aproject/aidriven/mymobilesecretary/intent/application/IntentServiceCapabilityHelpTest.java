package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentServiceCapabilityHelpTest {

    @Test
    void answersRecordedCapabilityQuestionsWithoutExposingInterpreterReason() {
        assertThat(IntentService.capabilityHelp("你現在能的能力範圍介紹"))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("建立、修改、完成與查詢待辦", "檢查撞期", "資訊不足時我會先問"));
        assertThat(IntentService.capabilityHelp("你能做什麼？")).isPresent();
        assertThat(IntentService.capabilityHelp("幫我建立明天的行程")).isEmpty();
    }
}
