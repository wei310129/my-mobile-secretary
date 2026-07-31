package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.ActivityIntentHandler;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationRepairSealedHoldoutTest {

    private static final Clock THURSDAY = Clock.fixed(
            Instant.parse("2026-07-16T02:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @ParameterizedTest
    @MethodSource("ungroundedCommands")
    void strictGroundingRejectsOperationsThatCannotBeTracedToThisTurn(
            String currentText, String inventedSource) {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_TASK, "不相關操作", inventedSource));

        IntentScript safe = IntentScriptSafetyPolicy.applyStrict(currentText, raw, THURSDAY);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().reason())
                .contains("無法對應", "不會執行")
                .doesNotContain("不相關操作");
    }

    @Test
    void strictGroundingAcceptsARepunctuatedFragmentFromTheCurrentTurn() {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_TASK, "補充咖啡濾紙", "補充 咖啡濾紙"));

        IntentScript safe = IntentScriptSafetyPolicy.applyStrict(
                "請幫我記下：補充咖啡濾紙。", raw, THURSDAY);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.CREATE_TASK);
    }

    @ParameterizedTest
    @MethodSource("quotedOnlyOperations")
    void textQuotedOnlyToCritiqueAnAnswerCannotBecomeANewOperation(
            String critique, String quotedSource) {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_TASK, "引用內容", quotedSource));

        IntentScript safe = IntentScriptSafetyPolicy.applyStrict(critique, raw, THURSDAY);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "請做個功能介紹",
            "我想了解你的能力範圍",
            "要怎麼用"
    })
    void unseenHelpRequestsOpenTheDeterministicMenu(String text) {
        assertThat(IntentService.capabilityHelp(text, ConversationSnapshot.empty()))
                .hasValueSatisfying(result -> {
                    assertThat(result.action()).isEqualTo(
                            IntentResult.Action.CAPABILITY_HELP_MENU);
                    assertThat(result.message()).contains("1.", "5.", "請選一類");
                });
    }

    @Test
    void helpChoicesRemainBoundToTheImmediatelyPreviousHelpTurn() {
        ConversationSnapshot menu = snapshot(IntentResult.Action.CAPABILITY_HELP_MENU);
        IntentResult shopping = IntentService.capabilityHelp("第四類", menu).orElseThrow();

        assertThat(shopping.action()).isEqualTo(IntentResult.Action.CAPABILITY_HELP_SHOPPING);
        assertThat(IntentService.capabilityHelp(
                "移除", snapshot(shopping.action())))
                .hasValueSatisfying(result -> {
                    assertThat(result.action()).isEqualTo(
                            IntentResult.Action.CAPABILITY_HELP_SHOPPING);
                    assertThat(result.message()).contains("購物", "刪除範例");
                });
        assertThat(IntentService.capabilityHelp("第四類", ConversationSnapshot.empty())).isEmpty();
        assertThat(IntentService.capabilityHelp("移除", ConversationSnapshot.empty())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "我不是在說你重複新增，是你不該憑空回覆",
            "並非重複項目，我是在說你答錯問題"
    })
    void duplicateNegationIsTreatedAsWrongAnswerFeedback(String text) {
        IntentResult result = ProductFeedbackBoundary.answer(text).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message())
                .contains("理解錯")
                .doesNotContain("確認目前有", "沒有完全同名");
    }

    @Test
    void duplicateComplaintWithoutStoredEvidenceDoesNotConfirmTheDiagnosis() {
        TaskService taskService = mock(TaskService.class);
        when(taskService.listOpenTasks()).thenReturn(List.of());
        ActivityIntentHandler handler = handler(taskService);

        IntentResult result = handler.handle(
                "待辦怎麼出現兩個一樣的？", feedback("DUPLICATE"));

        assertThat(result.message())
                .contains("沒有完全同名", "沒有刪除或修改")
                .doesNotContain("已確認目前有");
    }

    @Test
    void duplicateComplaintWithStoredEvidenceReportsCandidatesButDoesNotDelete() {
        TaskService taskService = mock(TaskService.class);
        Task first = Task.create(
                "換空氣濾網", null, TaskPriority.NORMAL, null, Instant.EPOCH);
        Task second = Task.create(
                "換空氣濾網", null, TaskPriority.NORMAL, null, Instant.EPOCH);
        when(taskService.listOpenTasks()).thenReturn(List.of(first, second));
        ActivityIntentHandler handler = handler(taskService);

        IntentResult result = handler.handle(
                "同一件換濾網的事被重複新增了", feedback("DUPLICATE"));

        assertThat(result.message())
                .contains("已確認", "換空氣濾網", "尚未刪除", "明確指定");
    }

    @ParameterizedTest
    @MethodSource("weekendQueries")
    void colloquialWeekendQueriesResolveWithoutModelGuessing(
            String text, LocalDate saturday, LocalDate sunday) {
        assertThat(IntentService.dailyScheduleDates(text, THURSDAY))
                .contains(List.of(saturday, sunday));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "把這個周末的行程全刪掉",
            "下個週末新增一個家庭聚餐行程"
    })
    void weekendMutationsNeverEnterTheReadOnlyOverviewShortcut(String text) {
        assertThat(IntentService.dailyScheduleDates(text, THURSDAY)).isEmpty();
    }

    @Test
    void helpTurnExpiresPreviouslyDisplayedTaskAndScheduleOrdinals() {
        ConversationContext context = context();
        context.rememberTaskList("101,102", NOW);
        context.rememberScheduleList("201,202", NOW);
        context.rememberExchange("AGENDA_LISTED", "列出安排", "1. A 2. B", NOW);

        context.rememberExchange(
                IntentResult.Action.CAPABILITY_HELP_MENU.name(),
                "介紹一下功能", "請選一類", NOW.plusSeconds(1));

        assertThat(context.getLastTaskListIds()).isNull();
        assertThat(context.getLastScheduleListIds()).isNull();
    }

    @Test
    void feedbackTurnExpiresPreviouslyDisplayedOrdinalsButKeepsNoHiddenSelection() {
        ConversationContext context = context();
        context.rememberTaskList("301,302", NOW);
        context.rememberScheduleList("401,402", NOW);
        context.rememberExchange("AGENDA_LISTED", "有哪些安排", "1. C 2. D", NOW);

        context.rememberExchange(
                IntentResult.Action.FEEDBACK_RECEIVED.name(),
                "剛剛答錯重點", "已保留問題", NOW.plusSeconds(1));

        assertThat(context.getLastTaskListIds()).isNull();
        assertThat(context.getLastScheduleListIds()).isNull();
    }

    private static Stream<Arguments> ungroundedCommands() {
        return Stream.of(
                Arguments.of("只想先看看你支援哪些事情", "明晚六點去取貨"),
                Arguments.of("這次只要解釋上一個回答", "下週二上午看診"),
                Arguments.of("不要建立任何東西", "午休前寄出合約"));
    }

    private static Stream<Arguments> quotedOnlyOperations() {
        return Stream.of(
                Arguments.of(
                        "你剛才寫「週五三點打電話給牙醫」，"
                                + "我只是引用來說明答錯，不是新指令",
                        "週五三點打電話給牙醫"),
                Arguments.of(
                        "這段『明早九點寄合約』只是前一則回覆的例子，請勿執行",
                        "明早九點寄合約"));
    }

    private static Stream<Arguments> weekendQueries() {
        return Stream.of(
                Arguments.of(
                        "幫我看本周末的行程",
                        LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19)),
                Arguments.of(
                        "下個週末列出行程",
                        LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 26)),
                Arguments.of(
                        "這個周末行程排了哪些？",
                        LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19)));
    }

    private static ActivityIntentHandler handler(TaskService taskService) {
        return new ActivityIntentHandler(
                taskService,
                mock(ScheduleService.class),
                mock(ScheduleFollowUpService.class),
                mock(ConversationContextService.class));
    }

    private static IntentScript script(IntentCommand... commands) {
        return new IntentScript(List.of(commands));
    }

    private static IntentCommand command(
            IntentCommand.Type type, String title, String sourceText) {
        return new IntentCommand(
                type, title, null, null, null, null, null, null,
                null, null, null, null, null, null, sourceText);
    }

    private static IntentCommand feedback(String reason) {
        return new IntentCommand(
                IntentCommand.Type.FEEDBACK, null, null, null, null, null, null, reason,
                null, null, null, null, null);
    }

    private static ConversationSnapshot snapshot(IntentResult.Action action) {
        return new ConversationSnapshot(
                null, null, null, List.of(), List.of(), action.name(), null, null);
    }

    private static ConversationContext context() {
        return ConversationContext.create(
                WorkspaceChannel.LINE,
                new ConversationScopeKey("a".repeat(64), 1),
                NOW);
    }
}
