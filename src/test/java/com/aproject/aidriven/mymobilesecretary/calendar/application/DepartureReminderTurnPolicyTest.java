package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DepartureReminderTurnPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "好，前五分鐘叫我",
        "可以，出發前5分鐘提醒我",
        "要，提前 ５ 分鐘通知我",
        "出發前二十五分鐘跟我說",
        "提前一百二十分鐘提醒"
    })
    void recognizesExplicitLeadWithoutFallingBackToAdaptive(String text) {
        var answer = DepartureReminderTurnPolicy.answer(text);

        assertThat(answer.action())
                .isEqualTo(DepartureReminderTurnPolicy.Action.EXPLICIT_LEAD);
        assertThat(answer.leadMinutes()).isIn(5, 25, 120);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "哪兩個？",
        "是哪兩個提醒？",
        "你剛剛幫我設了哪些提醒？",
        "目前有哪些提醒"
    })
    void recognizesReminderDetailFollowUps(String text) {
        assertThat(DepartureReminderTurnPolicy.asksForCurrentDetails(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"前五分鐘到車站", "哪兩個地點", "提前五分鐘出發"})
    void rejectsNeighboringStatementsWithoutReminderMeaning(String text) {
        assertThat(DepartureReminderTurnPolicy.answer(text).action())
                .isEqualTo(DepartureReminderTurnPolicy.Action.UNRECOGNIZED);
        assertThat(DepartureReminderTurnPolicy.asksForCurrentDetails(text)).isFalse();
    }
}
