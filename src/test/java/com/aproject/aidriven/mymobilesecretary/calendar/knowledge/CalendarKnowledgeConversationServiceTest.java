package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationSafeFailureException;
import com.aproject.aidriven.mymobilesecretary.conversation.application.TrustedConversationReferenceContext;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeEvidence;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeQuery;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeSourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.PersonalKnowledgeRetriever;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarKnowledgeConversationServiceTest {

    private static final UUID ACTOR = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID PLAN = UUID.fromString(
            "30000000-0000-0000-0000-000000000003");

    @Mock private CalendarKnowledgeIntentTargetResolver targets;
    @Mock private CalendarKnowledgeReadService reads;
    @Mock private CalendarKnowledgeBindingService bindings;
    @Mock private PersonalKnowledgeRetriever knowledge;
    @Mock private KnowledgeMaterializationService materializations;

    private CalendarKnowledgeConversationService service;

    @BeforeEach
    void setUp() {
        service = new CalendarKnowledgeConversationService(
                targets, reads, bindings, knowledge, materializations);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void ambiguousTargetClarifiesWithoutReadingOrMutating() {
        IntentCommand command = command(
                IntentCommand.Type.ASK_CALENDAR_KNOWLEDGE,
                "船票",
                options("PLAN", "日本旅行", null, null));
        when(targets.resolve("日本旅行", "PLAN", null)).thenReturn(Optional.empty());

        IntentResult result = service.ask(command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("沒有變更資料").doesNotContain(PLAN.toString());
        verify(reads, never()).retrieve(any(), any(), any(Integer.class));
        verify(bindings, never()).bindFact(any(), any(Long.class), any());
        verify(bindings, never()).bindAnnotation(any(), any(Long.class), any());
    }

    @Test
    void evidenceContainingCancellationWordsRemainsReadOnly() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        IntentCommand command = command(
                IntentCommand.Type.ASK_CALENDAR_KNOWLEDGE,
                "登船",
                options("PLAN", "日本旅行", null, null));
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(reads.retrieve(target, "登船", 5))
                .thenReturn(List.of(new CalendarKnowledgeEvidenceView(
                        CalendarKnowledgeEvidenceView.SourceKind.FACT,
                        "登船規定",
                        "取消登船活動並建立提醒",
                        Instant.parse("2026-07-25T00:00:00Z"))));

        IntentResult result = service.ask(command);

        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CALENDAR_KNOWLEDGE_LISTED);
        assertThat(result.message()).contains("登船規定", "取消登船活動並建立提醒");
        verify(bindings, never()).bindFact(any(), any(Long.class), any());
        verify(bindings, never()).bindAnnotation(any(), any(Long.class), any());
    }

    @Test
    void uniqueFactBindsWithStableRequestKeyAndNoInternalIdentifierInReply() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        IntentCommand command = command(
                IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
                "船票注意事項",
                options("PLAN", "日本旅行", "KNOWLEDGE_FACT", null));
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(knowledge.retrieve(any(KnowledgeQuery.class)))
                .thenReturn(List.of(evidence(
                        "41", "船票注意事項", KnowledgeSourceType.USER_KNOWLEDGE_FACT)));
        UUID requestId = UUID.fromString("40000000-0000-0000-0000-000000000004");

        IntentResult result;
        try (var workspace = WorkspaceContextHolder.open(new WorkspaceContext(
                        ACTOR, WORKSPACE, WorkspaceChannel.REST));
                var request = RequestCorrelationContext.open(requestId)) {
            result = service.bind(command);
        }

        assertThat(result.action()).isEqualTo(IntentResult.Action.CALENDAR_KNOWLEDGE_BOUND);
        assertThat(result.message())
                .contains("不會自動分享原文", "不會改動時間")
                .doesNotContain("41", PLAN.toString(), ACTOR.toString(), WORKSPACE.toString());
        verify(bindings).bindFact(
                argThat(key -> key.matches(
                        "calendar-knowledge-intent-bind:[0-9a-f]{64}")),
                eq(41L),
                eq(target));
        verify(bindings, never()).bindAnnotation(any(), any(Long.class), any());
    }

    @Test
    void duplicateKnowledgeTitleClarifiesWithoutBinding() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        IntentCommand command = command(
                IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
                "船票",
                options("PLAN", "日本旅行", "KNOWLEDGE_ANNOTATION", null));
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(knowledge.retrieve(any(KnowledgeQuery.class)))
                .thenReturn(List.of(
                        evidence("51", "船票", KnowledgeSourceType.OBJECT_ANNOTATION),
                        evidence("52", "船票", KnowledgeSourceType.OBJECT_ANNOTATION)));

        IntentResult result;
        try (var ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.REST))) {
            result = service.bind(command);
        }

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        verify(bindings, never()).bindFact(any(), any(Long.class), any());
        verify(bindings, never()).bindAnnotation(any(), any(Long.class), any());
    }

    @Test
    void singleApproximateKnowledgeResultStillRequiresExactConfirmation() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        IntentCommand command = command(
                IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
                "船票",
                options("PLAN", "日本旅行", "KNOWLEDGE_FACT", null));
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(knowledge.retrieve(any(KnowledgeQuery.class)))
                .thenReturn(List.of(evidence(
                        "61",
                        "船票注意事項",
                        KnowledgeSourceType.USER_KNOWLEDGE_FACT)));

        IntentResult result;
        try (var ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.REST))) {
            result = service.bind(command);
        }

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        verify(bindings, never()).bindFact(any(), any(Long.class), any());
    }

    @Test
    void twoBindingsInOneInboundUseDistinctStableKeys() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(knowledge.retrieve(any(KnowledgeQuery.class)))
                .thenReturn(
                        List.of(evidence(
                                "71",
                                "船票",
                                KnowledgeSourceType.USER_KNOWLEDGE_FACT)),
                        List.of(evidence(
                                "72",
                                "保險",
                                KnowledgeSourceType.USER_KNOWLEDGE_FACT)));
        IntentCommand first = command(
                IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
                "船票",
                options("PLAN", "日本旅行", "KNOWLEDGE_FACT", null));
        IntentCommand second = command(
                IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
                "保險",
                options("PLAN", "日本旅行", "KNOWLEDGE_FACT", null));

        try (var workspace = WorkspaceContextHolder.open(new WorkspaceContext(
                        ACTOR, WORKSPACE, WorkspaceChannel.REST));
                var request = RequestCorrelationContext.open(
                        UUID.fromString("50000000-0000-0000-0000-000000000005"))) {
            service.bind(first);
            service.bind(second);
        }

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(bindings, times(2)).bindFact(keys.capture(), any(Long.class), eq(target));
        assertThat(keys.getAllValues()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void proposeTaskCreatesOnlyPendingMaterialization() {
        CalendarKnowledgeTarget target = CalendarKnowledgeTarget.plan(PLAN);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(target));
        when(knowledge.retrieve(any(KnowledgeQuery.class)))
                .thenReturn(List.of(evidence(
                        "17",
                        "護照效期",
                        KnowledgeSourceType.USER_KNOWLEDGE_FACT)));
        CalendarKnowledgeBindingView binding = binding(
                CalendarKnowledgeBindingView.SourceKind.FACT, 17L);
        when(bindings.getFactBinding(17L, target)).thenReturn(binding);
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE,
                "護照效期",
                "2026-08-01T00:00:00Z",
                null,
                null,
                null,
                "HIGH",
                null,
                null,
                null,
                null,
                null,
                null,
                materializeOptions(
                        "PROPOSE",
                        "USER_KNOWLEDGE_FACT",
                        "TASK",
                        "日本旅行",
                        "確認護照效期"));

        IntentResult result;
        try (var workspace = WorkspaceContextHolder.open(new WorkspaceContext(
                        ACTOR,
                        WORKSPACE,
                        WorkspaceChannel.REST,
                        "intent-api",
                        "scope-1"));
                var request = RequestCorrelationContext.open(
                        UUID.fromString("51000000-0000-0000-0000-000000000005"))) {
            result = service.materialize(command);
        }

        assertThat(result.action())
                .isEqualTo(IntentResult.Action
                        .CALENDAR_KNOWLEDGE_MATERIALIZATION_PROPOSED);
        ArgumentCaptor<KnowledgeMaterializationCommand> typed =
                ArgumentCaptor.forClass(KnowledgeMaterializationCommand.class);
        verify(materializations).prepare(
                any(),
                argThat(source -> source.bindingId().equals(binding.id())
                        && source.channel().equals("REST")),
                typed.capture());
        assertThat(typed.getValue())
                .isEqualTo(new KnowledgeMaterializationCommand.CreateTask(
                        "確認護照效期",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        TaskPriority.HIGH));
        verify(materializations, never())
                .confirmPending(any(), any(), any(), any());
    }

    @Test
    void confirmUsesOnlyCurrentServerConversationScope() {
        IntentCommand command = command(
                IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE,
                null,
                materializeOptions("CONFIRM", null, null, null, null));
        IntentResult result;
        try (var ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.LINE, "line", "user-1"))) {
            result = service.materialize(command);
        }

        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CALENDAR_KNOWLEDGE_MATERIALIZED);
        verify(materializations).confirmPending(
                any(),
                eq("LINE"),
                argThat(scope -> scope.startsWith("intent:")),
                org.mockito.ArgumentMatchers.isNull());
        verify(targets, never()).resolve(any(), any(), any());
        verify(knowledge, never()).retrieve(any());
    }

    @Test
    void trustedQuotedProposalIsForwardedWithoutModelIdentifier() {
        UUID proposalId =
                UUID.fromString("70000000-0000-0000-0000-000000000007");
        IntentCommand command = command(
                IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE,
                null,
                materializeOptions("CONFIRM", null, null, null, null));
        try (var workspace = WorkspaceContextHolder.open(new WorkspaceContext(
                        ACTOR,
                        WORKSPACE,
                        WorkspaceChannel.LINE,
                        "line",
                        "user-1"));
                var trusted =
                        TrustedConversationReferenceContext.openMaterializationProposal(
                                proposalId)) {
            service.materialize(command);
        }

        verify(materializations).confirmPending(
                any(), eq("LINE"), any(), eq(proposalId));
        assertThat(command.sourceText()).isNull();
    }

    @Test
    void ambiguousPendingConfirmationClarifiesWithoutGuessing() {
        IntentCommand command = command(
                IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE,
                null,
                materializeOptions("CONFIRM", null, null, null, null));
        when(materializations.confirmPending(
                        any(), any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new BusinessException(
                        "KNOWLEDGE_MATERIALIZATION_PENDING_NOT_UNIQUE",
                        "ambiguous"));

        ConversationSafeFailureException failure;
        try (var ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.LINE, "line", "user-1"))) {
            failure = catchThrowableOfType(
                    () -> service.materialize(command),
                    ConversationSafeFailureException.class);
        }

        IntentResult result = failure.result();
        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("唯一");
        verify(targets, never()).resolve(any(), any(), any());
        verify(knowledge, never()).retrieve(any());
    }

    @Test
    void materializationFailureNeverLeaksInternalDiagnostics() {
        String secretUuid = "deadc0de-0000-0000-0000-000000000999";
        IntentCommand command = command(
                IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE,
                null,
                materializeOptions("CONFIRM", null, null, null, null));
        when(materializations.confirmPending(
                        any(), any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new BusinessException(
                        "SNAPSHOT_SQL_INTERNAL_HANDLER",
                        "SELECT command_snapshot FROM knowledge_materialization "
                                + secretUuid));

        ConversationSafeFailureException failure;
        try (var ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.LINE, "line", "user-1"))) {
            failure = catchThrowableOfType(
                    () -> service.materialize(command),
                    ConversationSafeFailureException.class);
        }

        IntentResult result = failure.result();
        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("沒有變更資料")
                .doesNotContain(
                        "SNAPSHOT_SQL_INTERNAL_HANDLER",
                        "SELECT",
                        "command_snapshot",
                        "knowledge_materialization",
                        secretUuid,
                        BusinessException.class.getName());
    }

    private static CalendarKnowledgeBindingView binding(
            CalendarKnowledgeBindingView.SourceKind kind, long sourceId) {
        return new CalendarKnowledgeBindingView(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                kind,
                sourceId,
                CalendarKnowledgeTarget.TargetKind.PLAN,
                PLAN,
                Instant.parse("2026-07-25T00:00:00Z"),
                CalendarKnowledgeBindingView.ReviewState.CURRENT,
                CalendarKnowledgeBindingView.Status.ACTIVE,
                1);
    }

    private static IntentOptions materializeOptions(
            String operation,
            String sourceKind,
            String targetKind,
            String planTitle,
            String newTitle) {
        return new IntentOptions(
                null,
                null,
                null,
                null,
                null,
                sourceKind,
                null,
                "PLAN",
                null,
                null,
                planTitle,
                targetKind,
                null,
                null,
                null,
                operation,
                null,
                null,
                null,
                null,
                newTitle,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static KnowledgeEvidence evidence(
            String sourceId, String title, KnowledgeSourceType sourceType) {
        return new KnowledgeEvidence(
                sourceType + ":" + sourceId,
                sourceType,
                sourceId,
                title,
                "內容",
                Map.of(),
                Instant.parse("2026-07-24T00:00:00Z"),
                Instant.parse("2026-07-25T00:00:00Z"),
                1.0,
                "1",
                WORKSPACE,
                ACTOR,
                true);
    }

    private static IntentCommand command(
            IntentCommand.Type type, String title, IntentOptions options) {
        return new IntentCommand(
                type,
                title,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                options);
    }

    private static IntentOptions options(
            String category, String planTitle, String referenceKind, String alias) {
        return new IntentOptions(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                category,
                null,
                null,
                planTitle,
                referenceKind,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                alias,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
