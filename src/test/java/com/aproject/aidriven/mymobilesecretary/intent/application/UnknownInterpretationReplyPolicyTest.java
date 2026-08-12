package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnknownInterpretationReplyPolicyTest {

    @Test
    void genericUnknownOffersBoundedSecretaryChoicesInsteadOfAnotherOpenEndedQuestion() {
        IntentResult result = UnknownInterpretationReplyPolicy.clarification(null);

        assertThat(result.message()).contains("待辦", "行程", "提醒");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void consecutiveUnknownTurnsNarrowTheQuestionWithoutRepeatingIt() {
        IntentResult first = UnknownInterpretationReplyPolicy.clarification(null);
        IntentResult second = UnknownInterpretationReplyPolicy.clarification(null, first.message());
        IntentResult third = UnknownInterpretationReplyPolicy.clarification(null, second.message());
        IntentResult fourth = UnknownInterpretationReplyPolicy.clarification(null, third.message());

        assertThat(second.message()).contains("先看今天安排").isNotEqualTo(first.message());
        assertThat(third.message()).contains("查看資料", "建立資料", "修改既有資料")
                .isNotEqualTo(second.message());
        assertThat(fourth.message())
                .as("a fourth unknown turn must keep narrowing instead of cycling")
                .isNotEqualTo(first.message())
                .isNotEqualTo(second.message())
                .isNotEqualTo(third.message());
        assertThat(java.util.List.of(first, second, third, fourth)).allSatisfy(result ->
                assertThat(result.message().chars().filter(value -> value == '？').count())
                        .isEqualTo(1));
    }

    @Test
    void durableQuestionCodesProgressAndEventuallyStopTheClarificationLoop() {
        IntentResult first = UnknownInterpretationReplyPolicy.clarification(null, null, null);
        IntentResult second = UnknownInterpretationReplyPolicy.clarification(
                null, null, first.nextQuestion().code());
        IntentResult third = UnknownInterpretationReplyPolicy.clarification(
                null, null, second.nextQuestion().code());
        IntentResult fourth = UnknownInterpretationReplyPolicy.clarification(
                null, null, third.nextQuestion().code());
        IntentResult fifth = UnknownInterpretationReplyPolicy.clarification(
                null, null, fourth.nextQuestion().code());
        IntentResult terminal = UnknownInterpretationReplyPolicy.clarification(
                null, null, fifth.nextQuestion().code());

        assertThat(java.util.List.of(first, second, third, fourth, fifth))
                .extracting(result -> result.nextQuestion().code())
                .doesNotHaveDuplicates();
        assertThat(terminal.action()).isEqualTo(IntentResult.Action.FAILURE_EXPLAINED);
        assertThat(terminal.nextQuestion()).isNull();
        assertThat(terminal.message()).contains("不會建立或修改資料");
    }
}
