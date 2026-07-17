package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import org.junit.jupiter.api.Test;

class IntentReplyFormatterTest {

    @Test
    void multilineReplyUsesCorrespondingEmojiAndListItems() {
        String formatted = IntentReplyFormatter.format(
                IntentResult.Action.TASKS_LISTED,
                "待辦事項\n洗衣服\n2. 買牛奶");

        assertThat(formatted).isEqualTo("📋 待辦事項\n- 洗衣服\n2. 買牛奶");
    }

    @Test
    void separateBlocksKeepBlankLineAndReceiveMeaningfulEmoji() {
        String formatted = IntentReplyFormatter.format(
                IntentResult.Action.SUGGESTION_MADE,
                "時間有衝突\n行程 A\n\n建議先移動行程 B\n改到上午");

        assertThat(formatted).isEqualTo(
                "⚠️ 時間有衝突\n- 行程 A\n\n💡 建議先移動行程 B\n- 改到上午");
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

        assertThat(result.message()).isEqualTo("🌦️ 今日天氣\n- 午後有雨");
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
}
