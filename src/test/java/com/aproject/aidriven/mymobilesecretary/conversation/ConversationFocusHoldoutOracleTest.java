package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationFocusHoldoutOracleTest {

    @Test
    void semanticReplyFactsKeepTypedStateMutationAndPrivacyChecksExact() {
        var capture = capture("TASK_CREATED", "📋 已建立待辦。\n\n目前先處理這件待辦。", 1L);
        var oracle = oracle("TASK_CREATED", List.of("已建立", "目前先處理"),
                List.of("UUID", "stack trace"), 1L);

        assertThatCode(() -> ConversationFocusHoldoutTest.assertMatches(capture, oracle))
                .doesNotThrowAnyException();
    }

    @Test
    void typedActionAndRequiredReplyFactsStillFailClosedWithoutLeakingContent() {
        var capture = capture("CLARIFICATION_NEEDED", "📋 已建立待辦。", 1L);

        assertThatThrownBy(() -> ConversationFocusHoldoutTest.assertMatches(capture,
                oracle("TASK_CREATED", List.of("已建立"), List.of(), 1L)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("scenarioIndex=1", "turnIndex=1", "field=typedAction");

        assertThatThrownBy(() -> ConversationFocusHoldoutTest.assertMatches(
                capture("TASK_CREATED", "📋 已建立待辦。", 1L),
                oracle("TASK_CREATED", List.of("目前先處理"), List.of(), 1L)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("field=requiredReplyFacts")
                .hasMessageNotContaining("目前先處理");
    }

    private static ConversationFocusHoldoutTest.CaptureArtifact capture(
            String action, String publicReply, long taskCount) {
        var turn = new ConversationFocusHoldoutTest.TurnCapture(
                action, publicReply, "TASK", 1L, taskCount, 0L, 0L, 1L, true);
        return new ConversationFocusHoldoutTest.CaptureArtifact(
                "2", "input-hash", List.of(
                        new ConversationFocusHoldoutTest.ScenarioCapture("scenario-1", List.of(turn))));
    }

    private static ConversationFocusHoldoutTest.HoldoutOracle oracle(
            String action, List<String> required, List<String> forbidden, long taskCount) {
        var turn = new ConversationFocusHoldoutTest.TurnOracle(
                action, required, forbidden, "TASK", 1L, taskCount, 0L, 0L, 1L, true);
        return new ConversationFocusHoldoutTest.HoldoutOracle(
                "1", "input-hash", "capture-hash", List.of(
                        new ConversationFocusHoldoutTest.ScenarioOracle("scenario-1", List.of(turn))));
    }
}
