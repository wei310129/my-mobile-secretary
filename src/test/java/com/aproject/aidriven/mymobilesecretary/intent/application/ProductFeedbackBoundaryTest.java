package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductFeedbackBoundaryTest {

    @Test
    void mixedFamilyContextAndGeneralizedSecretaryRulesAreProductFeedback() {
        String text = """
                明天的父親節活動是12點結束，而且結束後女兒同學（呂曼菲）的爸爸有約大家一起午餐。

                對了，每天就算不是上班日一般也都有早餐、午餐、晚餐等生活硬需求，當時間太緊的時候你要提醒要預留用餐行程，這比較不算一個行程，比較像是一個生活建議，就好比會影響睡覺時間，但還是可以排行程，只是在行程上或是最後要貼心提醒如果這樣安排晚上睡覺時間可能被壓縮或是午餐時間可能會來不及，給使用者的決定後就只要註記會受到影響即可。

                但前提是每個人的三餐時間並不一樣，你要先和使用者確認他們想要的時間，並且有寫固定行程（例如上班行程）可能有類似的，要有個優先順序來整合，例如我的上班行程中早餐會在到公司後吃，所以時間大概是9:10-20，但週末或假日早餐可能比較浮動，大概可以抓個8點到9點半之間吃。
                """;

        IntentResult result = ProductFeedbackBoundary.answer(text).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("功能改善問題紀錄").contains("不會建立待辦或行程");
    }

    @Test
    void shortCorrectionIsRecordedWithoutCallingAi() {
        IntentResult result = ProductFeedbackBoundary.answer("你沒有聽懂").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("理解錯了").contains("請直接告訴我");
    }

    @Test
    void ordinaryMealReminderAndFamilyEndTimeRemainBusinessMessages() {
        assertThat(ProductFeedbackBoundary.answer("提醒我中午十二點吃午餐")).isEmpty();
        assertThat(ProductFeedbackBoundary.answer("明天的父親節活動是12點結束")).isEmpty();
        assertThat(ProductFeedbackBoundary.answer("你沒有聽懂老師的訊息就要問我")).isEmpty();
    }

    @Test
    void explicitDevelopmentRequestDoesNotNeedToBeLong() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "這是一個開發需求，你卻當成我在問明天學校的父親節活動").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
    }

    @Test
    void genericTagArchitectureCorrectionIsFeedbackInsteadOfAnOldDraftAnswer() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "你的回應要調整，我希望用標籤式解耦去做連結，不要寫死在油漆資料欄位裡。")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
    }

    @Test
    void complaintQuotingAnUnrelatedEventDraftIsCapturedBeforeDraftRouting() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "你完全都沒聽懂我在幹嘛，我在回應青葉水泥漆訊息，你再跟我講開發者工作坊草稿？")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
    }
}
