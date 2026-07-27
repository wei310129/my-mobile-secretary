package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeIntentTargetResolver;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationSafeFailureException;
import com.aproject.aidriven.mymobilesecretary.conversation.application.TrustedConversationReferenceContext;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarAttachmentConversationServiceTest {

    @Mock private CalendarKnowledgeIntentTargetResolver targets;
    @Mock private CalendarAttachmentBindingService bindings;

    @Test
    void trustedQuotedMediaBindsExactNodeAndOnlyPreviewsBothShareLayers() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID nodeId =
                UUID.fromString("40000000-0000-0000-0000-000000000004");
        when(targets.resolve("日本旅行", "NODE", "登船"))
                .thenReturn(Optional.of(CalendarKnowledgeTarget.node(planId, nodeId)));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        IntentResult result;
        try (var trusted = TrustedConversationReferenceContext.open(null, 37L);
                var request = RequestCorrelationContext.open(
                        UUID.fromString(
                                "50000000-0000-0000-0000-000000000005"))) {
            result = service.bindAndPreviewShare(command());
        }

        assertThat(result.action())
                .isEqualTo(
                        IntentResult.Action
                                .CALENDAR_ATTACHMENT_BOUND_SHARE_PREVIEWED);
        assertThat(result.message())
                .contains("行程核心分享：尚未建立", "附件內容授權：尚未建立")
                .contains("不會自動");
        verify(bindings)
                .bind(
                        any(),
                        eq(37L),
                        eq(CalendarAttachmentTarget.node(planId, nodeId)),
                        eq("船票 PDF"),
                        eq(0));
    }

    @Test
    void missingTrustedQuoteClarifiesWithoutBinding() {
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        IntentResult result = service.bindAndPreviewShare(command());

        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        verify(bindings, never()).bind(any(), any(Long.class), any(), any(), any(Integer.class));
        verify(targets, never()).resolve(any(), any(), any());
    }

    @Test
    void inaccessibleTrustedMediaEscapesAsPreSanitizedTransactionalFailure() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID nodeId =
                UUID.fromString("40000000-0000-0000-0000-000000000004");
        CalendarAttachmentTarget target =
                CalendarAttachmentTarget.node(planId, nodeId);
        when(targets.resolve("日本旅行", "NODE", "登船"))
                .thenReturn(
                        Optional.of(
                                CalendarKnowledgeTarget.node(
                                        planId, nodeId)));
        when(bindings.bind(any(), eq(37L), eq(target), eq("船票 PDF"), eq(0)))
                .thenThrow(
                        new NotFoundException(
                                "StoredMedia", "private-media-seed"));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        ConversationSafeFailureException failure;
        try (var trusted =
                TrustedConversationReferenceContext.open(null, 37L)) {
            failure = catchThrowableOfType(
                    () -> service.bindAndPreviewShare(command()),
                    ConversationSafeFailureException.class);
        }

        assertThat(failure.result().action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(failure.result().message())
                .contains("這次沒有變更資料")
                .doesNotContain("private-media-seed", "StoredMedia");
    }

    @Test
    void unlinkOnlyRemovesBindingAndDoesNotDeleteOrReplaceMedia() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        CalendarAttachmentTarget target = CalendarAttachmentTarget.plan(planId);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(CalendarKnowledgeTarget.plan(planId)));
        CalendarAttachmentBindingView binding = binding(planId);
        when(bindings.findUniqueActiveForConversation(target, "船票 PDF"))
                .thenReturn(Optional.of(binding));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        IntentResult result = service.manage(manageCommand("UNLINK"));

        assertThat(result.action())
                .isEqualTo(IntentResult.Action.CALENDAR_ATTACHMENT_UNLINKED);
        assertThat(result.message()).contains("原始檔案仍保留");
        verify(bindings).unlink(binding.id(), binding.revision());
        verify(bindings, never()).replace(any(), any(), anyLong(), anyLong());
    }

    @Test
    void mutationConflictEscapesAsPreSanitizedTransactionalFailure() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        CalendarAttachmentTarget target =
                CalendarAttachmentTarget.plan(planId);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(
                        Optional.of(
                                CalendarKnowledgeTarget.plan(planId)));
        CalendarAttachmentBindingView binding = binding(planId);
        when(bindings.findUniqueActiveForConversation(target, "船票 PDF"))
                .thenReturn(Optional.of(binding));
        when(bindings.unlink(binding.id(), binding.revision()))
                .thenThrow(new BusinessException(
                        "ATTACHMENT_BINDING_REVISION_CONFLICT",
                        "internal revision conflict"));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        ConversationSafeFailureException failure =
                catchThrowableOfType(
                        () -> service.manage(manageCommand("UNLINK")),
                        ConversationSafeFailureException.class);

        assertThat(failure.result().action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(failure.result().message())
                .contains("這次沒有變更資料")
                .doesNotContain("internal revision conflict");
    }

    @Test
    void replaceRequiresTrustedNewMediaAndUsesBindingRevision() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        CalendarAttachmentTarget target = CalendarAttachmentTarget.plan(planId);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(CalendarKnowledgeTarget.plan(planId)));
        CalendarAttachmentBindingView binding = binding(planId);
        when(bindings.findUniqueActiveForConversation(target, "船票 PDF"))
                .thenReturn(Optional.of(binding));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        IntentResult missing = service.manage(manageCommand("REPLACE"));
        assertThat(missing.action())
                .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        verify(bindings, never()).replace(any(), any(), anyLong(), anyLong());

        IntentResult replaced;
        try (var trusted = TrustedConversationReferenceContext.open(null, 88L);
                var request = RequestCorrelationContext.open(
                        UUID.fromString(
                                "50000000-0000-0000-0000-000000000005"))) {
            replaced = service.manage(manageCommand("REPLACE"));
        }
        assertThat(replaced.action())
                .isEqualTo(IntentResult.Action.CALENDAR_ATTACHMENT_REPLACED);
        assertThat(replaced.message()).contains("舊的附件內容授權已失效");
        verify(bindings).replace(
                any(), eq(binding.id()), eq(88L), eq(binding.revision()));
    }

    @Test
    void permanentDeleteOnlyReturnsConfirmationPreview() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        CalendarAttachmentTarget target = CalendarAttachmentTarget.plan(planId);
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(CalendarKnowledgeTarget.plan(planId)));
        CalendarAttachmentBindingView binding = binding(planId);
        when(bindings.findUniqueActiveForConversation(target, "船票 PDF"))
                .thenReturn(Optional.of(binding));
        CalendarAttachmentConversationService service =
                new CalendarAttachmentConversationService(targets, bindings);

        IntentResult result = service.manage(manageCommand("DELETE_SOURCE"));

        assertThat(result.action())
                .isEqualTo(
                        IntentResult.Action
                                .CALENDAR_ATTACHMENT_DELETE_CONFIRMATION_REQUIRED);
        assertThat(result.message()).contains("目前尚未刪除", "明確確認");
        verify(bindings, never()).unlink(any(), anyLong());
        verify(bindings, never()).replace(any(), any(), anyLong(), anyLong());
    }

    private static CalendarAttachmentBindingView binding(UUID planId) {
        return new CalendarAttachmentBindingView(
                UUID.fromString("60000000-0000-0000-0000-000000000006"),
                37L,
                CalendarAttachmentTarget.TargetKind.PLAN,
                planId,
                "船票 PDF",
                0,
                CalendarAttachmentBindingView.Status.ACTIVE,
                3);
    }

    private static IntentCommand manageCommand(String operation) {
        return new IntentCommand(
                IntentCommand.Type.MANAGE_CALENDAR_ATTACHMENT,
                "船票 PDF",
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
                new IntentOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "PLAN",
                        null,
                        null,
                        "日本旅行",
                        null,
                        null,
                        null,
                        null,
                        operation,
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
                        null));
    }

    private static IntentCommand command() {
        return new IntentCommand(
                IntentCommand.Type.BIND_CALENDAR_ATTACHMENT_AND_PREVIEW_SHARE,
                "船票 PDF",
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
                new IntentOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "NODE",
                        null,
                        null,
                        "日本旅行",
                        null,
                        null,
                        null,
                        null,
                        "BIND_AND_PREVIEW_SHARE",
                        null,
                        null,
                        null,
                        "登船",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }
}
