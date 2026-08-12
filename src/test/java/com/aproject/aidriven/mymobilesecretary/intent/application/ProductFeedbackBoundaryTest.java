package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductFeedbackBoundaryTest {

    @Test
    void praiseReceivesWarmControlledAcknowledgementWithoutInventingMemory() {
        IntentResult result = ProductFeedbackBoundary.answer("你做得很好").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message())
                .containsAnyOf("謝謝您", "肯定", "您滿意就好", "幫到您")
                .doesNotContain("我會記住", "我會維持", "我會繼續");
        assertThat(result.nextQuestion()).isNull();
    }

    @Test
    void genericDissatisfactionActivelyClarifiesWithoutClaimingARerun() {
        IntentResult result = ProductFeedbackBoundary.answer("你做得很差").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("您最希望我先");
        assertThat(result.message())
                .doesNotContain("我會重新處理", "我會依您", "我會調整", "已重新處理");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
        assertThat(result.nextQuestion()).isNotNull();
    }

    @Test
    void productRuleAcknowledgementDoesNotPromiseUnsupportedFutureBehavior() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "這是一個功能需求，系統應該要用更自然的方式回答使用者").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message())
                .doesNotContain("我會依", "我會調整", "我會記住", "我會重新處理");
    }

    @Test
    void mixedFamilyContextAndGeneralizedSecretaryRulesAreProductFeedback() {
        String text = """
                明天的父親節活動是12點結束，而且結束後女兒同學（呂曼菲）的爸爸有約大家一起午餐。

                對了，每天就算不是上班日一般也都有早餐、午餐、晚餐等生活硬需求，當時間太緊的時候你要提醒要預留用餐行程，這比較不算一個行程，比較像是一個生活建議，就好比會影響睡覺時間，但還是可以排行程，只是在行程上或是最後要貼心提醒如果這樣安排晚上睡覺時間可能被壓縮或是午餐時間可能會來不及，給使用者的決定後就只要註記會受到影響即可。

                但前提是每個人的三餐時間並不一樣，你要先和使用者確認他們想要的時間，並且有寫固定行程（例如上班行程）可能有類似的，要有個優先順序來整合，例如我的上班行程中早餐會在到公司後吃，所以時間大概是9:10-20，但週末或假日早餐可能比較浮動，大概可以抓個8點到9點半之間吃。
                """;

        IntentResult result = ProductFeedbackBoundary.answer(text).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("謝謝您直接告訴我").contains("不會建立待辦或行程")
                .contains("不會修改既有待辦或行程")
                .doesNotContain("我會依", "我會調整", "我會記住")
                .doesNotContain("問題紀錄", "後端");
    }

    @Test
    void shortCorrectionIsRecordedWithoutCallingAi() {
        IntentResult result = ProductFeedbackBoundary.answer("你沒有聽懂").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("理解錯了").contains("還沒有重新處理")
                .contains("您希望我先更正哪一部分")
                .doesNotContain("從原操作續接", "我會重新處理");
        assertThat(result.nextQuestion()).isNotNull();
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

    @Test
    void yesterdayMultiParagraphDevelopmentInstructionCannotFallIntoBusinessRouting() {
        String text = """
                1. 行程已經有說是10點到12點了，你至少先推斷我應該是在12點要接。
                2. 如果你一次有好幾個問題要問使用者，你要用條列式。
                3. 我認為你要記得近期的對談主題及相關內容作為動作上下文，避免每次要求使用者重新輸入完整訊息。
                這是我的開發指示，不是要建立行程。
                """;

        IntentResult result = ProductFeedbackBoundary.answer(text).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("謝謝您直接告訴我")
                .doesNotContain("我會依", "我會調整", "我會記住")
                .doesNotContain("問題紀錄", "後端");
    }

    @Test
    void realFormattingCorrectionDoesNotFallIntoScheduleConfirmation() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "首先你的格式不對，1. 2. 3. 的內容如果有空行，那除了1.之外的項次之前也要空行")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("理解錯了", "您希望我先更正哪一部分")
                .doesNotContain("從原操作續接", "我會重新處理");
    }

    @Test
    void complaintAboutLeakingReasoningIsCapturedAsResponseCorrection() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "你把你的邏輯回給使用者要幹嘛？直接回答是哪一個，而且已經確認就不要再問")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
    }

    @Test
    void realComplaintNeverLeaksAnotherInternalIntentExplanation() {
        IntentResult result = ProductFeedbackBoundary.answer("完全不知所云").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("理解錯了", "還沒有重新處理")
                .doesNotContain("使用者說", "無法判斷意圖");
    }

    @Test
    void negatedDuplicateDiagnosisIsCapturedAsWrongAnswerFeedback() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "不是重複建立，而是不應該亂回答").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message())
                .contains("不是在說重複建立", "不會建立或修改", "還沒有重新處理")
                .doesNotContain("我會保留問題")
                .doesNotContain("你提醒得對，不應該重複建立");
        assertThat(result.nextQuestion()).isNotNull();
    }

    @Test
    void genericCorrectionAboutThePreviousReplyIsFeedback() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "我是在指出你上一則回答有錯，不是要建立任何資料").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("理解錯了", "不會建立");
    }

    @Test
    void unrelatedRepeatedReplyComplaintIsFeedback() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "為什麼你又回了和上一則無關的內容？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
    }

    @Test
    void taskScheduleRelationshipCorrectionNamesTheMissAndAsksOnlyOneRepairQuestion() {
        IntentResult result = ProductFeedbackBoundary.answer(
                "你沒有把待辦和行程的時間關聯一起看，才會回答錯重點").orElseThrow();

        assertThat(result.message())
                .contains("待辦", "行程", "關聯")
                .doesNotContain("請直接指出要更正的內容");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }
}
