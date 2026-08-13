package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationPendingQuestionServiceTest {

    @Test
    void currentPendingDoesNotCrossActorWorkspaceOrConversationScope() {
        UUID firstActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        WorkspaceContext first = new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST,
                "test", "thread-a");
        WorkspaceContext second = new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST,
                "test", "thread-b");
        ConversationScopeResolver resolver = mock(ConversationScopeResolver.class);
        ConversationFocusService focuses = mock(ConversationFocusService.class);
        ConversationPendingQuestionRepository repository = mock(ConversationPendingQuestionRepository.class);
        ConversationPendingQuestion firstPending = mock(ConversationPendingQuestion.class);
        when(firstPending.expireIfDue(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(resolver.current(first)).thenReturn(new ConversationScopeKey("a".repeat(64), 1));
        when(resolver.current(second)).thenReturn(new ConversationScopeKey("b".repeat(64), 1));
        when(repository.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                firstWorkspace, firstActor, WorkspaceChannel.TEST, "a".repeat(64),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING))
                .thenReturn(Optional.of(firstPending));
        when(repository.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                secondWorkspace, secondActor, WorkspaceChannel.TEST, "b".repeat(64),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING))
                .thenReturn(Optional.empty());
        ConversationPendingQuestionService service = new ConversationPendingQuestionService(resolver, focuses,
                repository, Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(first)) {
            assertThat(service.current()).contains(firstPending);
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(second)) {
            assertThat(service.current()).isEmpty();
        }

        verify(repository).findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                firstWorkspace, firstActor, WorkspaceChannel.TEST, "a".repeat(64),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING);
        verify(repository).findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                secondWorkspace, secondActor, WorkspaceChannel.TEST, "b".repeat(64),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING);
    }

    @Test
    void cancelUsesTheCurrentActorWorkspaceAndScopeAndCannotCancelAnotherWorkflow() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestedWorkflow = UUID.randomUUID();
        UUID foreignWorkflow = UUID.randomUUID();
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", "thread-a");
        ConversationScopeResolver resolver = mock(ConversationScopeResolver.class);
        ConversationFocusService focuses = mock(ConversationFocusService.class);
        ConversationPendingQuestionRepository repository = mock(ConversationPendingQuestionRepository.class);
        ConversationPendingQuestion pending = mock(ConversationPendingQuestion.class);
        when(resolver.current(context)).thenReturn(new ConversationScopeKey("a".repeat(64), 1));
        when(pending.getWorkflowId()).thenReturn(foreignWorkflow);
        when(repository.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                actorId, workspaceId, WorkspaceChannel.TEST, "a".repeat(64),
                com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING))
                .thenReturn(Optional.of(pending));
        ConversationPendingQuestionService service = new ConversationPendingQuestionService(resolver, focuses,
                repository, Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            assertThat(service.cancelCurrent(requestedWorkflow, hmac('d'))).isFalse();
        }

        verify(repository).findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                eq(workspaceId), eq(actorId), eq(WorkspaceChannel.TEST), eq("a".repeat(64)),
                eq(com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus.PENDING));
    }

    private static String hmac(char value) {
        return String.valueOf(value).repeat(64);
    }
}
