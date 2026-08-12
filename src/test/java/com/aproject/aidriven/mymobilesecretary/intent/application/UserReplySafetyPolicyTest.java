package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusTransitionNotice;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserReplySafetyPolicyTest {

    @Test
    void internalRoutingDetailsNeverReachTheUser() {
        IntentResult result = IntentResult.message(IntentResult.Action.BATCH_EXECUTED,
                "使用者確認是某行程（lastScheduleId=14），需由系統回覆。Java 驗證原因：missing");

        assertThat(result.message())
                .contains("不需要您理解系統內部細節")
                .doesNotContain("lastScheduleId", "Java", "使用者確認", "需由系統");
    }

    @Test
    void unavailableReplyOnlySaysWhatTheUserNeedsToDo() {
        IntentResult result = IntentResult.aiUnavailable(
                "AI 暫時無法使用", "missing title", null);

        assertThat(result.message())
                .contains("資料沒有異動", "可以補上")
                .doesNotContain("AI", "Java", "驗證", "問題紀錄", "後端", "我會接著處理");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Intent=CREATE_SCHEDULE",
        "intent=CHECK_FEASIBILITY",
        "validation failed",
        "PlannerIntentHandler failed",
        "schema mismatch",
        "reason=missing",
        "field=title",
        "startAt is required",
        "referenceTitle missing",
        "nodeId=123",
        "workspaceId=456",
        "UUID=789"
    })
    void structuredDiagnosticsAreReplacedByOnePublicQuestion(String leaked) {
        String response = IntentResult.message(
                        IntentResult.Action.CLARIFICATION_NEEDED,
                        leaked)
                .message();

        assertThat(response)
                .contains("沒有據此建立或修改資料", "您要處理哪一筆？")
                .doesNotContain(leaked, "我會接著處理");
        assertThat(response.chars().filter(character -> character == '？').count())
                .isEqualTo(1);
    }

    @Test
    void ordinarySecretaryReplyIsNotRewritten() {
        String message = "目前路線需要約 45 分鐘。這次要用開車還是大眾運輸？";

        assertThat(UserReplySafetyPolicy.sanitize(message)).isEqualTo(message);
    }

    @Test
    void focusDecorationCannotAppendInternalDiagnosticsAfterReplyValidation() {
        IntentResult result = IntentResult.message(
                        IntentResult.Action.CLARIFICATION_NEEDED,
                        "我還需要一項資訊。")
                .withFocusNotice(FocusTransitionNotice.forTransition(
                        FocusTransitionType.ENTER,
                        null,
                        "handler=SeededInternalHandler schema=seeded_internal_table",
                        null));

        assertThat(new PublicConversationResponseService().finalizeReply(result).message())
                .doesNotContain("SeededInternalHandler", "seeded_internal_table", "handler", "schema");
    }
}
