package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentHandlerExceptionMapperTest {

    @Test
    void genericFailureAsksOneTypedQuestionWithoutExposingExceptionDetail() {
        var result = IntentHandlerExceptionMapper.clarification(
                new IllegalArgumentException(
                        "validation failed for title, dueAt, placeId and handler=SeededHandler"));

        assertThat(result.nextQuestion().code()).isEqualTo("handler.target-name");
        assertThat(result.message())
                .contains("你要處理的名稱是什麼")
                .doesNotContain("validation", "dueAt", "placeId", "SeededHandler");
    }
}
