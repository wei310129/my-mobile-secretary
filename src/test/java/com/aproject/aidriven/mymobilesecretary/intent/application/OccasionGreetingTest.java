package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 回覆要有人味:偵測到場合就附一句祝賀,但不要多、抱怨場合不加。 */
class OccasionGreetingTest {

    @Test
    void birthdayCueAppendsOneGreetingLine() {
        IntentResult base = IntentResult.message(
                IntentResult.Action.RESTAURANT_BOOKING_INFO, "幫你查好餐廳了");

        IntentResult decorated = OccasionGreeting.decorate("週五訂餐廳幫女兒慶生", base);

        assertThat(decorated.message()).contains("🎂", "生日快樂");
    }

    @Test
    void celebrationCueCongratulates() {
        IntentResult base = IntentResult.message(IntentResult.Action.TASKS_LISTED, "還有 2 件待辦:");

        IntentResult decorated = OccasionGreeting.decorate("慶功宴前還有什麼要做", base);

        assertThat(decorated.message()).contains("🎉 恭喜");
    }

    @Test
    void plainRequestStaysUntouched() {
        IntentResult base = IntentResult.message(IntentResult.Action.TASKS_LISTED, "還有 2 件待辦:");

        assertThat(OccasionGreeting.decorate("還有什麼要做", base)).isSameAs(base);
    }

    @Test
    void feedbackComplaintsNeverCelebrate() {
        // 使用者在抱怨時道賀非常失禮,即使話中帶「生日」也不加
        IntentResult base = IntentResult.feedbackReceived();

        IntentResult decorated = OccasionGreeting.decorate("你把生日行程排錯了", base);

        assertThat(decorated.message()).doesNotContain("🎂");
    }

    @Test
    void onlyFirstMatchedOccasionIsUsed() {
        IntentResult base = IntentResult.message(IntentResult.Action.TASKS_LISTED, "還有 1 件待辦:");

        IntentResult decorated = OccasionGreeting.decorate("生日兼升遷慶功要準備什麼", base);

        assertThat(decorated.message()).contains("🎂").doesNotContain("🎉");
    }
}
