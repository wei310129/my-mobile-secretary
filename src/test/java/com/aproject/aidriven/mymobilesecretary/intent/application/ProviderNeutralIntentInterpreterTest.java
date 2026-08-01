package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderNeutralIntentInterpreterTest {

    private static final Instant NOW = Instant.parse("2026-08-01T02:00:00Z");

    private final AnthropicIntentInterpreter legacy = mock(AnthropicIntentInterpreter.class);
    private final ScheduleAnalysisUnderstandingClient client =
            mock(ScheduleAnalysisUnderstandingClient.class);
    private final ProviderNeutralIntentInterpreter interpreter =
            new ProviderNeutralIntentInterpreter(legacy, client, new ScheduleAnalysisRoutePolicy());

    @Test
    void routesGeneralizedCompoundAnalysisToOneSpecializedUnderstandingCall() {
        String text = "分析下禮拜的行程負荷，包含最忙日期、最長項目和相鄰空檔";
        when(client.available()).thenReturn(true);
        when(client.understand(text)).thenReturn(new ScheduleAnalysisUnderstandingClient.Decision(
                List.of(
                        ScheduleAnalysisUnderstandingClient.Facet.BUSIEST_DAY,
                        ScheduleAnalysisUnderstandingClient.Facet.LONGEST_ITEM,
                        ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS),
                false));

        IntentScript result = interpreter.interpret(text, NOW, ConversationSnapshot.empty());

        assertThat(result.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                IntentCommand.Type.UNKNOWN);
        assertThat(result.commands()).allSatisfy(command ->
                assertThat(command.sourceText()).isEqualTo(text));
        assertThat(result.commands().getLast().reason())
                .contains("相鄰空檔", "不會建立或修改資料")
                .doesNotContain("OpenAI", "Luna", "schema", "Intent");
        verify(client).understand(text);
        verifyNoInteractions(legacy);
    }

    @Test
    void mixedRequestKeepsReliableReadOnlyFacetAndRejectsTheOtherOperation() {
        String text = "找下週最長行程，然後新增週五兩點開會";
        when(client.available()).thenReturn(true);
        when(client.understand(text)).thenReturn(new ScheduleAnalysisUnderstandingClient.Decision(
                List.of(ScheduleAnalysisUnderstandingClient.Facet.LONGEST_ITEM), true));

        IntentScript result = interpreter.interpret(text, NOW);

        assertThat(result.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                IntentCommand.Type.UNKNOWN);
        assertThat(result.commands().getLast().reason())
                .contains("其他要求", "不會建立或修改資料");
        assertThat(result.commands()).noneMatch(command ->
                IntentService.isPotentiallyMutating(command.type()));
        verify(client).understand(text);
        verifyNoInteractions(legacy);
    }

    @Test
    void nonCandidateUsesExactlyOneLegacyCall() {
        String text = "下週有什麼行程？";
        IntentScript expected = new IntentScript(List.of(command(IntentCommand.Type.LIST_SCHEDULES)));
        when(legacy.interpret(text, NOW, ConversationSnapshot.empty())).thenReturn(expected);

        IntentScript result = interpreter.interpret(text, NOW, ConversationSnapshot.empty());

        assertThat(result).isSameAs(expected);
        verify(legacy).interpret(text, NOW, ConversationSnapshot.empty());
        verifyNoInteractions(client);
    }

    @Test
    void unavailableSpecializedProviderUsesLegacyWithoutAttemptingIt() {
        String text = "下週哪一天行程最多、最忙？";
        IntentScript expected = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY)));
        when(client.available()).thenReturn(false);
        when(legacy.interpret(text, NOW, ConversationSnapshot.empty())).thenReturn(expected);

        IntentScript result = interpreter.interpret(text, NOW, ConversationSnapshot.empty());

        assertThat(result).isSameAs(expected);
        verify(client, never()).understand(text);
        verify(legacy).interpret(text, NOW, ConversationSnapshot.empty());
    }

    @Test
    void specializedFailureNeverFallsThroughToASecondModelCall() {
        String text = "下週耗時最久的是哪一筆行程？";
        when(client.available()).thenReturn(true);
        when(client.understand(text)).thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> interpreter.interpret(text, NOW, ConversationSnapshot.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
        verify(client).understand(text);
        verifyNoInteractions(legacy);
    }

    @Test
    void inconsistentAdjacentGapDecisionFailsClosedWithoutLegacyCall() {
        String text = "下星期最久的那筆活動左右還有多少空白時間";
        when(client.available()).thenReturn(true);
        when(client.understand(text)).thenReturn(new ScheduleAnalysisUnderstandingClient.Decision(
                List.of(ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS), false));

        assertThatThrownBy(() -> interpreter.interpret(text, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LONGEST_ITEM");
        verifyNoInteractions(legacy);
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
