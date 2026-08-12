package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretaryTurnRouterTest {

    @Test
    void genericTodayQuestionUsesUnifiedSecretaryAgenda() {
        IntentCommand command = SecretaryTurnRouter.route("今天有什麼事？").orElseThrow();
        assertThat(command.type()).isEqualTo(IntentCommand.Type.LIST_AGENDA);
        assertThat(command.safeOptions().filter()).isEqualTo("TODAY");
    }

    @Test
    void shortFollowUpUsesUnifiedSecretaryAgenda() {
        assertThat(SecretaryTurnRouter.route("接下來呢").orElseThrow().type())
                .isEqualTo(IntentCommand.Type.LIST_AGENDA);
    }

    @Test
    void explicitDomainsRemainScoped() {
        assertThat(SecretaryTurnRouter.route("我還有哪些待辦").orElseThrow().type())
                .isEqualTo(IntentCommand.Type.LIST_TASKS);
        assertThat(SecretaryTurnRouter.route("列出明天行程").orElseThrow().type())
                .isEqualTo(IntentCommand.Type.LIST_SCHEDULES);
    }

    @Test
    void mutationAndPlaceQuestionsNeverUseReadOnlyShortcut() {
        assertThat(SecretaryTurnRouter.route("新增一個待辦")).isEmpty();
        assertThat(SecretaryTurnRouter.route("取消明天行程")).isEmpty();
        assertThat(SecretaryTurnRouter.route("測試商場在哪裡")).isEmpty();
    }

    @Test
    void recommendationsAndAnalyticalQuestionsRemainForTheInterpreter() {
        assertThat(SecretaryTurnRouter.route("晚點有什麼適合順路處理")).isEmpty();
        assertThat(SecretaryTurnRouter.route("推薦我接下來可以完成的事情")).isEmpty();
        assertThat(SecretaryTurnRouter.route("比較本週行程並找出最忙的一天")).isEmpty();
        assertThat(SecretaryTurnRouter.route("列出最長活動前後各有多少空檔")).isEmpty();
    }
}
