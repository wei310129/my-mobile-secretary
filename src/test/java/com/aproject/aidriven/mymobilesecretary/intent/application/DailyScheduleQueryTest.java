package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyScheduleQueryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-16T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordedOverviewPhrasesBypassIntentGuessing() {
        assertThat(IntentService.dailyScheduleDate("給我明天的行程", CLOCK))
                .contains(LocalDate.of(2026, 7, 17));
        assertThat(IntentService.dailyScheduleDate(
                "我的意思是你要把明天固定行程和當明天行程總整之後給我", CLOCK))
                .contains(LocalDate.of(2026, 7, 17));
        assertThat(IntentService.dailyScheduleDate(
                "我的意思是我要今天的你要把固定行程和當日行程總整之後給我", CLOCK))
                .contains(LocalDate.of(2026, 7, 16));
        assertThat(IntentService.dailyScheduleDate("昨天的行程", CLOCK))
                .contains(LocalDate.of(2026, 7, 15));
        assertThat(IntentService.dailyScheduleDate("上禮拜五的行程", CLOCK))
                .contains(LocalDate.of(2026, 7, 10));
        assertThat(IntentService.dailyScheduleDate("這週五有什麼行程？", CLOCK))
                .contains(LocalDate.of(2026, 7, 17));
        assertThat(IntentService.dailyScheduleDate("週六行程", CLOCK))
                .contains(LocalDate.of(2026, 7, 18));
    }

    @Test
    void overviewTermQuestionAndInternalReasoningAreHandledForUsers() {
        assertThat(IntentService.asksWhatContainedItemMeans("當日項目是指？")).isTrue();
        assertThat(IntentService.asksWhatContainedItemMeans("當日項目是哪個？")).isTrue();
        assertThat(IntentService.userFacingUnknownReason(
                "使用者是在詢問上一則回覆，無法對應到任何能力類型"))
                .contains("追問上一則回覆", "名稱或清單編號")
                .doesNotContain("能力類型");
        assertThat(IntentService.userFacingUnknownReason(
                "java.lang.IllegalStateException at com.example.SecretRepository: "
                        + "SQL column api_token, request 123e4567-e89b-12d3-a456-426614174000"))
                .contains("無法判斷", "不會建立或修改資料")
                .doesNotContain("java", "com.example", "SQL", "column", "api_token",
                        "123e4567-e89b-12d3-a456-426614174000");
        assertThat(IntentService.userFacingUnknownReason(
                "router confidence below threshold; candidate CREATE_SCHEDULE score 0.31"))
                .contains("無法判斷", "不會建立或修改資料")
                .doesNotContain("router", "confidence", "CREATE_SCHEDULE", "0.31");
    }

    @Test
    void scheduleCreationIsNotMistakenForOverviewQuery() {
        assertThat(IntentService.dailyScheduleDate("幫我排明天的專案行程", CLOCK)).isEmpty();
        assertThat(IntentService.dailyScheduleDate(
                        "幫我規劃明天早上九點到捷運台北車站的行程", CLOCK))
                .isEmpty();
        assertThat(IntentService.dailyScheduleDate(
                        "幫我規畫明天早上九點到捷運台北車站的行程", CLOCK))
                .isEmpty();
        assertThat(IntentService.dailyScheduleDate("明天上午十點安排一個行程", CLOCK)).isEmpty();
        assertThat(IntentService.dailyScheduleDate("取消昨天的行程", CLOCK)).isEmpty();
        assertThat(IntentService.dailyScheduleDate("把上禮拜五的行程刪掉", CLOCK)).isEmpty();
    }

    @Test
    void weekendQueriesResolveToSaturdayAndSundayWithoutUsingTheModel() {
        assertThat(IntentService.dailyScheduleDates("這週末有什麼行程？", CLOCK))
                .contains(List.of(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19)));
        assertThat(IntentService.dailyScheduleDates("下週末的行程", CLOCK))
                .contains(List.of(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 26)));
        assertThat(IntentService.dailyScheduleDates("週末行程", CLOCK))
                .contains(List.of(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19)));
        assertThat(IntentService.dailyScheduleDates("取消這週末的行程", CLOCK)).isEmpty();
    }

    @Test
    void bareWeekendOnSundayKeepsTheCurrentWeekendAndHandlesYearBoundary() {
        Clock sunday = Clock.fixed(Instant.parse("2026-07-19T02:00:00Z"), ZoneOffset.UTC);
        Clock yearEnd = Clock.fixed(Instant.parse("2026-12-31T02:00:00Z"), ZoneOffset.UTC);

        assertThat(IntentService.dailyScheduleDates("週末有哪些行程？", sunday))
                .contains(List.of(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19)));
        assertThat(IntentService.dailyScheduleDates("下週末有哪些行程？", yearEnd))
                .contains(List.of(LocalDate.of(2027, 1, 9), LocalDate.of(2027, 1, 10)));
    }

    @Test
    void mergeConfirmationIsRecognizedDeterministically() {
        assertThat(IntentService.isScheduleMergeConfirmation("確認併入上班固定行程")).isTrue();
        assertThat(IntentService.isScheduleMergeConfirmation("給我明天的行程")).isFalse();
    }

    @Test
    void mergeRejectionIsRecognizedBeforeConfirmation() {
        assertThat(IntentService.isScheduleMergeRejection("簡報排練不要併到上班固定行程")).isTrue();
        assertThat(IntentService.isScheduleMergeRejection("不要併入，你有聽懂嗎？")).isTrue();
        assertThat(IntentService.isScheduleMergeRejection("取消併入")).isTrue();
        assertThat(IntentService.isScheduleMergeRejection("確認併入上班固定行程")).isFalse();
        assertThat(IntentService.isScheduleMergeRejection("給我明天的行程")).isFalse();
        // 「不要併入固定行程」同時含確認關鍵字,拒絕判斷必須先執行才不會誤確認
        assertThat(IntentService.isScheduleMergeRejection("不要併入固定行程")).isTrue();
        assertThat(IntentService.isScheduleMergeConfirmation("不要併入固定行程")).isTrue();
    }

    @Test
    void decisionDelegationIsRecognizedDeterministically() {
        assertThat(IntentService.isDecisionDelegation("你自己看著辦")).isTrue();
        assertThat(IntentService.isDecisionDelegation("你決定就好")).isTrue();
        assertThat(IntentService.isDecisionDelegation("隨便你安排")).isTrue();
        assertThat(IntentService.isDecisionDelegation("給我明天的行程")).isFalse();
        assertThat(IntentService.isDecisionDelegation("取消簡報排練")).isFalse();
    }

}
