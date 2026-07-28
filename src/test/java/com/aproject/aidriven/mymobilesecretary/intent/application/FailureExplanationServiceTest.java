package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FailureExplanationServiceTest {

    @Test
    void explainsTheMissingUserInformationWithoutBackendDetails() {
        IntentCommand command = new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE,
                "倒垃圾", null, "2026-07-16T22:00:00+08:00", null,
                null, "NORMAL", null, null, null, null, null, false);
        IntentResult failed = IntentResult.aiUnavailable("解析結果不完整",
                "建立行程必須同時有 startAt 與 endAt", command);
        ConversationSnapshot snapshot = snapshot(failed.action().name(), failed.message());

        IntentResult result = FailureExplanationService.answer("為什麼失敗？", snapshot).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAILURE_EXPLAINED);
        assertThat(result.message())
                .contains("缺行程的結束時間或預計時長", "資料沒有異動")
                .doesNotContain("Java", "AI", "startAt", "endAt", "type=");
    }

    @Test
    void givesAnActionableRetryWhenOldFailureHasNoDetails() {
        ConversationSnapshot snapshot = snapshot(IntentResult.Action.AI_UNAVAILABLE.name(),
                "⚠️ 解析結果不完整。\n- 我沒有建立任何待辦");

        IntentResult result = FailureExplanationService.answer("剛才怎麼了", snapshot).orElseThrow();

        assertThat(result.message()).contains("沒有完成", "資料也沒有異動", "日期與時間")
                .doesNotContain("舊版", "Java", "AI");
    }

    @Test
    void ignoresUnrelatedMessages() {
        assertThat(FailureExplanationService.answer("今天要做什麼", ConversationSnapshot.empty()))
                .isEmpty();
    }

    private static ConversationSnapshot snapshot(String action, String assistantText) {
        return new ConversationSnapshot(null, null, null, List.of(), List.of(),
                action, "今天晚上10點要去倒垃圾", assistantText);
    }
}
