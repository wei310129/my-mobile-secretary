package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UncertainScheduleConditionConversationServiceTest {

    private final UncertainScheduleConditionConversationService service =
            new UncertainScheduleConditionConversationService();

    @Test
    void unknownPreviousEventOverrunAsksToKeepOneVersionAndRemind() {
        IntentResult result = service.answer(
                "週五十點的會議如果前一場拖到就延後半小時，沒拖到就照原定，我現在還不知道會不會拖")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains(
                "不會同時建立", "兩個互斥版本", "先保留原定時段", "提醒你確認", "同一筆行程延後半小時");
    }

    @Test
    void commonSpokenVariantsAreAlsoProtected() {
        assertThat(service.answer(
                "星期五十點開會，若前一場延誤就順延，否則維持原時間，但現在不確定會不會延誤"))
                .isPresent();
    }

    @Test
    void KnownDecisionAndUnrelatedConditionalStayOnGeneralPath() {
        assertThat(service.answer("前一場確定拖延，週五十點會議延後半小時")).isEmpty();
        assertThat(service.answer("如果下雨就提醒我帶傘，現在還不知道")).isEmpty();
    }
}
