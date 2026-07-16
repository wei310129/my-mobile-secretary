package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    }

    @Test
    void scheduleCreationIsNotMistakenForOverviewQuery() {
        assertThat(IntentService.dailyScheduleDate("幫我排明天的專案行程", CLOCK)).isEmpty();
        assertThat(IntentService.dailyScheduleDate("明天上午十點安排一個行程", CLOCK)).isEmpty();
    }

    @Test
    void mergeConfirmationIsRecognizedDeterministically() {
        assertThat(IntentService.isScheduleMergeConfirmation("確認併入上班固定行程")).isTrue();
        assertThat(IntentService.isScheduleMergeConfirmation("給我明天的行程")).isFalse();
    }
}
