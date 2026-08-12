package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationLatencyPolicyTest {

    private final ConversationLatencyPolicy policy = new ConversationLatencyPolicy();

    @Test
    void deterministicSecretaryQueriesDoNotNeedLoadingAnimation() {
        assertThat(policy.shouldShowLoading("今天有什麼事")).isFalse();
        assertThat(policy.shouldShowLoading("列出明天行程")).isFalse();
    }

    @Test
    void modelAndUnknownPathsRequestLoadingAnimation() {
        assertThat(policy.shouldShowLoading("幫我處理這件事")).isTrue();
        assertThat(policy.shouldShowLoading("測試商場在哪裡")).isTrue();
    }
}
