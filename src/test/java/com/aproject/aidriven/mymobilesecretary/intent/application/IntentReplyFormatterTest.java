package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import org.junit.jupiter.api.Test;

class IntentReplyFormatterTest {

    @Test
    void ordinarySecretaryRepliesDoNotReceiveDecorativeEmojiPrefixes() {
        assertThat(IntentReplyFormatter.format(
                IntentResult.Action.PLACE_INFO,
                "我知道，您說的是位於新北市的「捷運大坪林站」。"))
                .isEqualTo("我知道，您說的是位於新北市的「捷運大坪林站」。");
        assertThat(IntentReplyFormatter.format(
                IntentResult.Action.FEEDBACK_RECEIVED,
                "收到，這次沒有符合您的期待。"))
                .isEqualTo("收到，這次沒有符合您的期待。");
        assertThat(IntentReplyFormatter.format(
                IntentResult.Action.SOCIAL_REPLIED,
                "早安。今天有需要我先替您整理的事情嗎？"))
                .isEqualTo("早安。今天有需要我先替您整理的事情嗎？");
        assertThat(IntentReplyFormatter.format(
                IntentResult.Action.CLARIFICATION_NEEDED,
                "您希望我先確認哪一部分？"))
                .isEqualTo("您希望我先確認哪一部分？");
    }

    @Test
    void multilineReplyUsesCorrespondingEmojiAndListItems() {
        String formatted = IntentReplyFormatter.format(
                IntentResult.Action.TASKS_LISTED,
                "待辦事項\n洗衣服\n2. 買牛奶");

        assertThat(formatted).isEqualTo("待辦事項\n- 洗衣服\n2. 買牛奶");
    }

    @Test
    void separateBlocksKeepBlankLineAndReceiveMeaningfulEmoji() {
        String formatted = IntentReplyFormatter.format(
                IntentResult.Action.SUGGESTION_MADE,
                "時間有衝突\n行程 A\n\n建議先移動行程 B\n改到上午");

        assertThat(formatted).isEqualTo(
                "時間有衝突\n- 行程 A\n\n建議先移動行程 B\n- 改到上午");
    }

    @Test
    void formattingIsIdempotent() {
        String once = IntentReplyFormatter.format(
                IntentResult.Action.SCHEDULE_INFO,
                "📅 行程資訊\n- 07/16 09:00");

        assertThat(IntentReplyFormatter.format(IntentResult.Action.SCHEDULE_INFO, once))
                .isEqualTo(once);
    }

    @Test
    void intentResultFormatsMessageAtCreationTime() {
        IntentResult result = new IntentResult(
                IntentResult.Action.WEATHER_INFO,
                "今日天氣\n午後有雨",
                null,
                null);

        assertThat(result.message()).isEqualTo("今日天氣\n- 午後有雨");
    }

    @Test
    void typedPlainPresentationSurvivesTheFinalPublicBoundary() {
        IntentResult result = IntentResult.plainMessage(
                IntentResult.Action.PLACE_INFO, "我知道，您說的是位於新北市的「測試地點」。");

        assertThat(new PublicConversationResponseService().finalizeReply(result).message())
                .isEqualTo("我知道，您說的是位於新北市的「測試地點」。");
    }

    @Test
    void manuallyAuthoredDecorativeEmojiCannotBypassTheFinalToneBoundary() {
        IntentResult result = IntentResult.message(
                IntentResult.Action.PLACE_INFO,
                "📍 我知道，您說的是位於新北市的「測試地點」。\n\n"
                        + "🛠️ 這次沒有建立或修改資料。");

        assertThat(new PublicConversationResponseService().finalizeReply(result).message())
                .isEqualTo("我知道，您說的是位於新北市的「測試地點」。\n\n"
                        + "這次沒有建立或修改資料。");
    }

    @Test
    void unsupportedMemoryClaimFailsClosedAtTheFinalBoundary() {
        IntentResult result = IntentResult.plainMessage(
                IntentResult.Action.FEEDBACK_RECEIVED, "謝謝您，我會記住這種處理方式。");

        assertThat(new PublicConversationResponseService().finalizeReply(result).message())
                .isEqualTo("收到。這項內容目前沒有保存成可長期沿用的偏好。");
    }

    @Test
    void unsupportedRepairClaimCannotPretendThatWorkStartedOrCompleted() {
        IntentResult result = IntentResult.plainMessage(
                IntentResult.Action.FEEDBACK_RECEIVED, "我會重新處理，而且我已重新整理完成。");

        assertThat(new PublicConversationResponseService().finalizeReply(result).message())
                .isEqualTo("收到，這次沒有符合您的期待。");
    }

    @Test
    void verifiedPreferenceAndRepairEvidencePreserveTruthfulClaims() {
        IntentResult preference = IntentResult.plainMessage(
                IntentResult.Action.CONTEXT_UPDATED,
                "我已記住這項偏好。",
                PublicReplyEvidence.PREFERENCE_COMMITTED);
        IntentResult repair = IntentResult.plainMessage(
                IntentResult.Action.FEEDBACK_RECEIVED,
                "我已重新整理完成。",
                PublicReplyEvidence.REPAIR_COMPLETED);

        PublicConversationResponseService boundary = new PublicConversationResponseService();
        assertThat(boundary.finalizeReply(preference).message()).isEqualTo("我已記住這項偏好。");
        assertThat(boundary.finalizeReply(repair).message()).isEqualTo("我已重新整理完成。");
    }

    @Test
    void typedMutationActionSuppliesRuntimeEvidenceForItsCommittedClaim() {
        IntentResult result = IntentResult.plainMessage(
                IntentResult.Action.KNOWLEDGE_SAVED,
                "我已記住這項明確指定的偏好。");

        PublicConversationReply finalized =
                new PublicConversationResponseService().finalizeReply(result);

        assertThat(finalized.message()).isEqualTo("我已記住這項明確指定的偏好。");
        assertThat(finalized.evidence()).contains(PublicReplyEvidence.MUTATION_COMMITTED);
    }

    @Test
    void directPublicReplyKeepsTypedNotificationEvidenceThroughTheFinalBoundary() {
        PublicConversationReply reply = PublicConversationReply.terminal(
                "收到，我已開始處理「行程整理」，完成後會通知您。",
                PublicConversationReply.TerminalState.IN_PROGRESS,
                PublicReplyEvidence.ASYNC_COMMITTED,
                PublicReplyEvidence.NOTIFICATION_COMMITTED);

        PublicConversationReply finalized =
                new PublicConversationResponseService().finalizeReply(reply);

        assertThat(finalized.message()).contains("已開始處理", "完成後會通知您");
        assertThat(finalized.evidence()).containsExactlyInAnyOrder(
                PublicReplyEvidence.ASYNC_COMMITTED,
                PublicReplyEvidence.NOTIFICATION_COMMITTED);
    }

    @Test
    void notificationUsesItsTitleToSelectEmoji() {
        ReminderNotification notification = new ReminderNotification(
                java.util.UUID.fromString("10000000-0000-0000-0000-000000000101"),
                java.util.UUID.fromString("10000000-0000-0000-0000-000000000001"),
                java.util.UUID.randomUUID(),
                "test",
                null,
                null,
                "待安排事項",
                "目前有空檔\n\n待安排事項:\n整理文件\n回覆信件\n\n要排進行程嗎?");

        assertThat(notification.message())
                .isEqualTo("📅 目前有空檔\n\n"
                        + "📅 待安排事項:\n- 整理文件\n- 回覆信件\n\n"
                        + "❓ 要排進行程嗎?");
    }

    @Test
    void batchReplySeparatesEveryNumberedSectionWithABlankLine() {
        IntentResult result = IntentResult.batchExecuted(java.util.List.of(
                "第一件\n- 細節", "第二件\n- 細節", "第三件"));

        assertThat(result.message()).isEqualTo(
                "一次處理 3 件:\n\n1.第一件\n- 細節\n\n2.第二件\n- 細節\n\n3.第三件");
    }
}
