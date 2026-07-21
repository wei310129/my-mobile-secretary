package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserReplySafetyPolicyTest {

    @Test
    void internalRoutingDetailsNeverReachTheUser() {
        IntentResult result = IntentResult.message(IntentResult.Action.BATCH_EXECUTED,
                "使用者確認是某行程（lastScheduleId=14），需由系統回覆。Java 驗證原因：missing");

        assertThat(result.message())
                .contains("不需要你理解系統內部細節")
                .doesNotContain("lastScheduleId", "Java", "使用者確認", "需由系統");
    }

    @Test
    void unavailableReplyOnlySaysWhatTheUserNeedsToDo() {
        IntentResult result = IntentResult.aiUnavailable(
                "AI 暫時無法使用", "missing title", null);

        assertThat(result.message())
                .contains("沒有建立或修改資料", "請補充")
                .doesNotContain("AI", "Java", "驗證", "問題紀錄", "後端");
    }
}
