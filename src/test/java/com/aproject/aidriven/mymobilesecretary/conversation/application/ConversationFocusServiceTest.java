package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusHead;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusRootKind;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransition;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusHeadRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.FocusTransitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationFocusServiceTest {

    @Test
    void duplicateInboundReturnsExistingTransitionWithoutAnotherMutation() {
        ConversationScopeResolver resolver = mock(ConversationScopeResolver.class);
        ConversationFocusHeadRepository heads = mock(ConversationFocusHeadRepository.class);
        ConversationFocusRepository focuses = mock(ConversationFocusRepository.class);
        FocusTransitionRepository transitions = mock(FocusTransitionRepository.class);
        ConversationFocusService service = new ConversationFocusService(resolver, heads, focuses,
                transitions, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
        WorkspaceContext context = new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(),
                WorkspaceChannel.TEST, "test", "scope-a");
        ConversationScopeKey scope = new ConversationScopeKey("e".repeat(64), 1);
        ConversationFocus existing = ConversationFocus.workflow(scope, WorkspaceChannel.TEST,
                ConversationFocusRootKind.WORKFLOW, "PROJECT", UUID.randomUUID(), "大阪旅行",
                Instant.parse("2026-07-21T00:00:00Z"));

        when(resolver.current(context)).thenReturn(scope);
        when(transitions.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(), "f".repeat(64)))
                .thenReturn(Optional.of(FocusTransition.create(scope, context.channel(), 0, 1,
                        FocusTransitionType.ENTER, null, existing.getId(), "f".repeat(64),
                        Instant.parse("2026-07-21T00:00:00Z"))));
        when(focuses.findById(existing.getId())).thenReturn(Optional.of(existing));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            assertThat(service.enterWorkflow("PROJECT", existing.getWorkflowId(), "大阪旅行",
                    "f".repeat(64))).isSameAs(existing);
        }

        verify(focuses).findById(existing.getId());
        assertThatThrownBy(() -> service.resume(UUID.randomUUID(), "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void switchSuspendsPriorFocusAndRecordsExactlyOneTypedTransition() {
        ConversationScopeResolver resolver = mock(ConversationScopeResolver.class);
        ConversationFocusHeadRepository heads = mock(ConversationFocusHeadRepository.class);
        ConversationFocusRepository focuses = mock(ConversationFocusRepository.class);
        FocusTransitionRepository transitions = mock(FocusTransitionRepository.class);
        ConversationFocusService service = new ConversationFocusService(resolver, heads, focuses,
                transitions, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
        WorkspaceContext context = new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(),
                WorkspaceChannel.TEST, "test", "scope-a");
        ConversationScopeKey scope = new ConversationScopeKey("a".repeat(64), 1);
        ConversationFocus previous = ConversationFocus.workflow(scope, WorkspaceChannel.TEST,
                ConversationFocusRootKind.WORKFLOW, "PROJECT", UUID.randomUUID(), "東京旅行",
                Instant.parse("2026-07-21T00:00:00Z"));
        when(resolver.current(context)).thenReturn(scope);
        when(transitions.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(), "b".repeat(64)))
                .thenReturn(Optional.empty());
        when(focuses.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus.ACTIVE))
                .thenReturn(Optional.of(previous));
        when(heads.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest()))
                .thenReturn(Optional.of(ConversationFocusHead.create(scope, WorkspaceChannel.TEST,
                        Instant.parse("2026-07-21T00:00:00Z"))));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            service.switchWorkflow("PROJECT", UUID.randomUUID(), "大阪旅行", "b".repeat(64));
        }

        ArgumentCaptor<FocusTransition> saved = ArgumentCaptor.forClass(FocusTransition.class);
        verify(transitions).save(saved.capture());
        assertThat(previous.getStatus()).isEqualTo(
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus.SUSPENDED);
        assertThat(saved.getValue().getType()).isEqualTo(FocusTransitionType.SWITCH);
        assertThat(saved.getValue().getFromFocusId()).isEqualTo(previous.getId());
    }
}
