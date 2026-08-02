package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CalendarIntentDraftRequestPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "先幫我留草稿，明天下午開會,true",
        "先準備提案，不要放進去,true",
        "先不要排進行事曆，只整理給我,true",
        "明天開會，但不要直接建立,true",
        "明天下午三點幫我排開會,false",
        "先幫我排明天的會議,false",
        "不要建立待辦，只建立行程,false",
        "先看看明天有沒有空,false"
    })
    void recognizesOnlyBoundedExplicitDraftGrammar(String text, boolean expected) {
        assertThat(CalendarIntentDraftRequestPolicy.isDraftOnly(text)).isEqualTo(expected);
    }
}
