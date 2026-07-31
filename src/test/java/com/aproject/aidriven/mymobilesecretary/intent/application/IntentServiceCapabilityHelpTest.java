package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntentServiceCapabilityHelpTest {

    @Test
    void capabilityQuestionShowsDeterministicTopLevelMenu() {
        assertThat(IntentService.capabilityHelp("你現在能做到什麼？", ConversationSnapshot.empty()))
                .hasValueSatisfying(result -> {
                    assertThat(result.action()).isEqualTo(IntentResult.Action.CAPABILITY_HELP_MENU);
                    assertThat(result.message())
                            .contains("1.", "待辦", "2.", "行程", "5.", "提醒")
                            .contains("請選一類");
                });
        assertThat(IntentService.capabilityHelp("有哪些功能？", ConversationSnapshot.empty()))
                .isPresent();
        assertThat(IntentService.capabilityHelp("幫我建立明天的行程",
                ConversationSnapshot.empty())).isEmpty();
    }

    @Test
    void menuSelectionStartsWithCreationExampleAndOffersOtherOperations() {
        ConversationSnapshot menu = snapshot(IntentResult.Action.CAPABILITY_HELP_MENU);

        assertThat(IntentService.capabilityHelp("2", menu))
                .hasValueSatisfying(result -> {
                    assertThat(result.action())
                            .isEqualTo(IntentResult.Action.CAPABILITY_HELP_CALENDAR);
                    assertThat(result.message())
                            .contains("建立範例", "明天下午", "行程")
                            .contains("修改", "刪除", "查詢");
                });
    }

    @Test
    void topicOperationChoiceIsBoundToTheImmediatelyPreviousHelpTopic() {
        ConversationSnapshot calendar = snapshot(IntentResult.Action.CAPABILITY_HELP_CALENDAR);

        assertThat(IntentService.capabilityHelp("查詢", calendar))
                .hasValueSatisfying(result -> {
                    assertThat(result.action())
                            .isEqualTo(IntentResult.Action.CAPABILITY_HELP_CALENDAR);
                    assertThat(result.message()).contains("查詢範例", "這週末");
                });
        assertThat(IntentService.capabilityHelp("2", ConversationSnapshot.empty())).isEmpty();
        assertThat(IntentService.capabilityHelp("查詢", ConversationSnapshot.empty())).isEmpty();
    }

    private static ConversationSnapshot snapshot(IntentResult.Action action) {
        return new ConversationSnapshot(null, null, null, List.of(), List.of(),
                action.name(), null, null);
    }
}
