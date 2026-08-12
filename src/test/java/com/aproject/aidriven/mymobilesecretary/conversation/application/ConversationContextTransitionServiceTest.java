package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationContextTransitionServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"繼續", "繼續完成", "傳什麼？"})
    void orphanRoutePendingRecoversWithOneFreshRouteQuestion(String text) {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = orphanRoutePending();
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.cancelCurrent(pending.getWorkflowId(), "d".repeat(64)))
                .thenReturn(true);
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        when(lifecycle.describe(pending)).thenReturn(Optional.empty());
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer(text, mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> {
                    assertThat(result.message())
                            .contains("先前的路線規劃", "行事曆沒有新增資料", "起點、目的地與時間")
                            .doesNotContain("倒垃圾", "NotFound", "workflow");
                    assertThat(result.nextQuestion()).isNotNull();
                    assertThat(result.nextQuestion().code()).isEqualTo("route.new-request");
                });

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).cancelCurrent(
                pending.getWorkflowId(), "d".repeat(64));
    }

    @Test
    void completeNewRouteReplacesOrphanPointerAndContinuesInTheSameTurn() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = orphanRoutePending();
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.cancelCurrent(pending.getWorkflowId(), "d".repeat(64)))
                .thenReturn(true);
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        when(lifecycle.describe(pending)).thenReturn(Optional.empty());
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();
        IntentCommand route = command(IntentCommand.Type.PLAN_ROUTE_ITINERARY);

        assertThat(service.interceptBeforeExecution(
                        "幫我規劃明天早上九點從捷運大坪林到高鐵桃園站",
                        route, mutations::incrementAndGet, "d".repeat(64)))
                .isEmpty();

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).cancelCurrent(
                pending.getWorkflowId(), "d".repeat(64));
        verify(lifecycle, never()).stage(route);
    }

    @Test
    void readOnlyInterjectionDoesNotConsumeOrRewritePendingContext() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.general-buffer");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog());
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.interceptBeforeExecution(
                        "明天天氣如何？",
                        command(IntentCommand.Type.ASK_WEATHER),
                        mutations::incrementAndGet,
                        "c".repeat(64)))
                .isEmpty();

        assertThat(mutations).hasValue(0);
        verify(pendingQuestions, never()).beginContextChoice("c".repeat(64));
    }

    @Test
    void ambiguousStartCreatesOneTypedChoiceBeforeDomainExecution() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.general-buffer");
        ConversationPendingQuestion changed = pending("route.general-buffer");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        UUID deferredWorkflowId = UUID.randomUUID();
        changed.beginContextChoice(
                "c".repeat(64), deferredWorkflowId, Instant.parse("2026-08-02T01:00:01Z"));
        when(pendingQuestions.beginContextChoice("c".repeat(64), deferredWorkflowId))
                .thenReturn(Optional.of(changed));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        IntentCommand routeCommand = command(IntentCommand.Type.PLAN_ROUTE_ITINERARY);
        when(lifecycle.describe(pending)).thenReturn(Optional.of(
                routeOperation(pending.getWorkflowId())));
        when(lifecycle.stage(routeCommand)).thenReturn(Optional.of(new
                ConversationOperationLifecycleContributor.Operation(
                        "route", "CALENDAR_DRAFT", deferredWorkflowId, null,
                        "大坪林到新店站", true)));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        var result = service.interceptBeforeExecution(
                "幫我規劃明天早上九點的路線",
                routeCommand,
                mutations::incrementAndGet,
                "c".repeat(64));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value.nextQuestion().code()).isEqualTo("conversation.context-target");
            assertThat(value.message()).isEqualTo(
                    "您目前還有「大坪林到台北車站」尚未完成。"
                            + "這次指令要繼續目前操作，還是保留目前進度並開始新的操作？");
        });
        assertThat(mutations).hasValue(1);
        assertThat(changed.getDeferredWorkflowId()).isEqualTo(deferredWorkflowId);
    }

    @Test
    void completedRouteAuxiliaryQuestionDoesNotBlockANewOperation() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.departure-reminder");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.answerCurrent("route.departure-reminder")).thenReturn(true);
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();
        IntentCommand nextRoute = command(IntentCommand.Type.PLAN_ROUTE_ITINERARY);

        assertThat(service.interceptBeforeExecution(
                        "幫我規劃明天十點從捷運新店站到台北車站",
                        nextRoute, mutations::incrementAndGet, "c".repeat(64)))
                .isEmpty();

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).answerCurrent("route.departure-reminder");
        verify(pendingQuestions, never()).beginContextChoice(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(lifecycle, never()).stage(nextRoute);
    }

    @Test
    void contextChoiceAcceptsNaturalShortContinuation() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.general-buffer");
        pending.beginContextChoice("c".repeat(64), Instant.parse("2026-08-02T01:00:01Z"));
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.resumeInterruptedQuestion("d".repeat(64)))
                .thenAnswer(ignored -> {
                    pending.resumeInterruptedQuestion(
                            "d".repeat(64), Instant.parse("2026-08-02T01:00:02Z"));
                    return Optional.of(pending);
                });
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        var operation = routeOperation(pending.getWorkflowId());
        when(lifecycle.describe(pending)).thenReturn(Optional.of(operation));
        when(lifecycle.resumeQuestion(operation, "route.general-buffer"))
                .thenReturn(Optional.of(generalBufferQuestion()));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer("繼續完成", mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> {
                    assertThat(result.message())
                            .contains(
                                    "目前正在處理：",
                                    "從捷運大坪林到高鐵桃園站",
                                    "目前進度：",
                                    "正在設定這趟路線的前後緩衝時間",
                                    "下一步：",
                                    "前10、後15");
                    assertThat(result.nextQuestion()).isNotNull();
                    assertThat(result.nextQuestion().code()).isEqualTo("route.general-buffer");
                });

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).resumeInterruptedQuestion("d".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "處理到哪了？",
            "做到哪了？",
            "進度如何？",
            "你在處理什麼？",
            "現在在忙什麼？",
            "現在處理到哪裡了？",
            "現在在處理哪個項目？",
            "目前進度是什麼？"
    })
    void currentOperationStatusReorientsWithoutConsumingPendingState(String text) {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.origin-context");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        var operation = routeOperation(pending.getWorkflowId());
        var question = new ConversationOperationLifecycleContributor.ResumeQuestion(
                "從公司到高鐵桃園站",
                "已保留今天17:15出發與目的地；目前正在確認出發地點，路線尚未查詢，行程尚未建立。",
                "route.origin-context",
                "route.origin",
                "這趟要從哪個已確認地點出發？",
                10);
        when(lifecycle.describe(pending)).thenReturn(Optional.of(operation));
        when(lifecycle.resumeQuestion(operation, "route.origin-context"))
                .thenReturn(Optional.of(question));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer(text, mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> {
                    assertThat(result.message())
                            .contains(
                                    "目前正在處理：",
                                    "從公司到高鐵桃園站",
                                    "目前進度：",
                                    "目前子步驟：",
                                    "已保留：",
                                    "尚缺：",
                                    "今天17:15出發",
                                    "路線尚未查詢",
                                    "行程尚未建立",
                                    "下一步：",
                                    "哪個已確認地點出發");
                    assertThat(result.nextQuestion()).isNotNull();
                    assertThat(result.nextQuestion().code()).isEqualTo("route.origin-context");
                });

        assertThat(mutations).hasValue(0);
        verify(pendingQuestions, never()).answerCurrent("route.origin-context");
        verify(pendingQuestions, never()).cancelCurrent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "處理公司地址",
            "進度報告明天交",
            "哪個項目需要處理？",
            "如何處理這個地點？"
    })
    void ordinaryWorkSentencesDoNotBecomeCurrentOperationStatus(String text) {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);

        assertThat(service.answer(text, () -> {}, "d".repeat(64))).isEmpty();
        verify(pendingQuestions).current();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void contextChoiceAcceptsNaturalShortNewOperationAnswer() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.general-buffer");
        pending.beginContextChoice("c".repeat(64), Instant.parse("2026-08-02T01:00:01Z"));
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.requestNewOperationContent("d".repeat(64)))
                .thenReturn(Optional.of(pending));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog());
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer("新的", mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> assertThat(result.message())
                        .contains("先保留", "請告訴我要開始的新操作內容"));

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).requestNewOperationContent("d".repeat(64));
    }

    @Test
    void legacyRouteIdentityUsesExactTypedLifecycleDescriptor() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        UUID routeDraftId = UUID.randomUUID();
        ConversationPendingQuestion pending = legacyPending(routeDraftId);
        pending.beginContextChoice("c".repeat(64), Instant.parse("2026-08-02T01:00:01Z"));
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.resumeInterruptedQuestion("d".repeat(64)))
                .thenAnswer(ignored -> {
                    pending.resumeInterruptedQuestion(
                            "d".repeat(64), Instant.parse("2026-08-02T01:00:02Z"));
                    return Optional.of(pending);
                });
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        when(lifecycle.describe(pending)).thenReturn(Optional.of(routeOperation(routeDraftId)));
        when(lifecycle.resumeQuestion(
                        routeOperation(routeDraftId), "route.general-buffer"))
                .thenReturn(Optional.of(generalBufferQuestion()));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);

        assertThat(service.answer("繼續", () -> {}, "d".repeat(64)))
                .hasValueSatisfying(result -> {
                    assertThat(result.message())
                            .contains("從捷運大坪林到高鐵桃園站")
                            .doesNotContain("目前未完成的操作");
                    assertThat(result.focusBinding().domain()).isEqualTo("CALENDAR_DRAFT");
                    assertThat(result.focusBinding().workflowId()).isEqualTo(routeDraftId);
                });
    }

    @Test
    void routeLifecycleCanRecoverAfterGenericUnknownQuestionOverwroteTheVisibleStep() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        UUID routeDraftId = UUID.randomUUID();
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "task", routeDraftId, "intent.unknown-action", null,
                "b".repeat(64), Instant.parse("2026-08-02T01:01:00Z"),
                Instant.parse("2026-08-02T01:00:00Z"));
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        when(lifecycle.describe(pending)).thenReturn(Optional.of(routeOperation(routeDraftId)));
        when(lifecycle.resumeQuestion(
                        routeOperation(routeDraftId), "intent.unknown-action"))
                .thenReturn(Optional.of(generalBufferQuestion()));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);

        assertThat(service.answer("繼續", () -> {}, "d".repeat(64)))
                .hasValueSatisfying(result -> {
                    assertThat(result.message())
                            .contains("從捷運大坪林到高鐵桃園站", "前10、後15");
                    assertThat(result.nextQuestion().code()).isEqualTo("route.general-buffer");
                });
    }

    @Test
    void clearAndRestartClosesCurrentRouteLifecycleAtAnyQuestionStep() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.general-buffer");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        var operation = routeOperation(pending.getWorkflowId());
        when(lifecycle.describe(pending)).thenReturn(Optional.of(operation));
        when(lifecycle.closeCurrentTree(operation, "d".repeat(64)))
                .thenReturn(new ConversationOperationLifecycleService.ClearResult(
                        operation.safeLabel(), 1, 1, 2, true));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer(
                        "全部清除重來", mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> assertThat(result.message())
                        .contains("已清除未完成的路線規劃", "已建立的行程沒有變更", "重新告訴我"));

        assertThat(mutations).hasValue(1);
        verify(lifecycle).closeCurrentTree(operation, "d".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"取消", "取消這次的行程建立", "這次不要了"})
    void currentOperationCancellationClosesOnlyTheTypedUnfinishedRoute(String text) {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.direct-overlap");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        var operation = routeOperation(pending.getWorkflowId());
        when(lifecycle.describe(pending)).thenReturn(Optional.of(operation));
        when(lifecycle.closeCurrent(operation, "d".repeat(64)))
                .thenReturn(new ConversationOperationLifecycleService.ClearResult(
                        operation.safeLabel(), 1, 1, 1, true));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);
        AtomicInteger mutations = new AtomicInteger();

        assertThat(service.answer(text, mutations::incrementAndGet, "d".repeat(64)))
                .hasValueSatisfying(result -> assertThat(result.message())
                        .contains("已取消未完成的路線規劃", "從捷運大坪林到高鐵桃園站")
                        .contains("已建立的行程沒有變更")
                        .doesNotContain("UUID", "workflow", "route.direct-overlap"));

        assertThat(mutations).hasValue(1);
        verify(lifecycle).closeCurrent(operation, "d".repeat(64));
    }

    @Test
    void datedCalendarCancellationIsNotConsumedAsCurrentOperationControl() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("route.direct-overlap");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        ConversationOperationLifecycleService lifecycle =
                mock(ConversationOperationLifecycleService.class);
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog(), lifecycle);

        assertThat(service.answer(
                        "取消明天九點的行程", () -> {}, "d".repeat(64)))
                .isEmpty();

        verify(lifecycle, never()).closeCurrent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void trustedQuoteOverridesPendingContextChoice() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        when(pendingQuestions.current()).thenReturn(Optional.of(pending("route.general-buffer")));
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog());

        try (TrustedConversationReferenceContext.Scope ignored =
                TrustedConversationReferenceContext.openDraft(UUID.randomUUID())) {
            assertThat(service.interceptBeforeExecution(
                            "幫我規劃明天早上九點半的路線",
                            command(IntentCommand.Type.PLAN_ROUTE_ITINERARY),
                            () -> {},
                            "c".repeat(64)))
                    .isEmpty();
        }

        verify(pendingQuestions, never()).beginContextChoice("c".repeat(64));
    }

    @Test
    void typedWeekdayAnswerContinuesAndCompletesPendingQuestionAfterSuccess() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = pending("task.recurrence-weekday");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.answerCurrent("task.recurrence-weekday")).thenReturn(true);
        ConversationContextTransitionService service = new ConversationContextTransitionService(
                pendingQuestions, new ConversationFocusCapabilityCatalog());
        IntentResult successful = mock(IntentResult.class);
        when(successful.task()).thenReturn(mock(Task.class));
        AtomicInteger mutations = new AtomicInteger();
        IntentCommand command = command(IntentCommand.Type.CREATE_TASK);

        assertThat(service.interceptBeforeExecution(
                        "每週五提醒我交週報", command, mutations::incrementAndGet,
                        "c".repeat(64)))
                .isEmpty();
        service.completeNewOperationIfApplicable(
                "每週五提醒我交週報", command, successful,
                mutations::incrementAndGet, "c".repeat(64));

        assertThat(mutations).hasValue(1);
        verify(pendingQuestions).answerCurrent("task.recurrence-weekday");
        verify(pendingQuestions, never()).beginContextChoice("c".repeat(64));
    }

    private static ConversationPendingQuestion pending(String code) {
        Instant now = Instant.parse("2026-08-02T01:00:00Z");
        return ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar_draft", UUID.randomUUID(), code, "大坪林到台北車站",
                "b".repeat(64), now.plusSeconds(60), now);
    }

    private static ConversationPendingQuestion legacyPending(UUID workflowId) {
        Instant now = Instant.parse("2026-08-02T01:00:00Z");
        return ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "task", workflowId, "route.general-buffer", null,
                "b".repeat(64), now.plusSeconds(60), now);
    }

    private static ConversationPendingQuestion orphanRoutePending() {
        Instant now = Instant.parse("2026-08-02T01:00:00Z");
        return ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "task", UUID.randomUUID(), "route.activity-duration", "倒垃圾",
                "b".repeat(64), now.plusSeconds(60), now);
    }

    private static ConversationOperationLifecycleContributor.Operation routeOperation(
            UUID workflowId) {
        return new ConversationOperationLifecycleContributor.Operation(
                "route", "CALENDAR_DRAFT", workflowId, null,
                "從捷運大坪林到高鐵桃園站", true);
    }

    private static ConversationOperationLifecycleContributor.ResumeQuestion generalBufferQuestion() {
        return new ConversationOperationLifecycleContributor.ResumeQuestion(
                "從捷運大坪林到高鐵桃園站",
                "正在設定這趟路線的前後緩衝時間。",
                "route.general-buffer",
                "route.general-buffer",
                "這類行程前面與後面通常各要預留幾分鐘？可以回覆「前10、後15」。",
                10);
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(
                type, null, null, null, null, null, null, null, null, null, null, null, false);
    }
}
