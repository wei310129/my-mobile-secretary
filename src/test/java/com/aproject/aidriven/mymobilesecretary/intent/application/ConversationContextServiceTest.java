package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.ConversationContextRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class ConversationContextServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID WORKSPACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000101");
    private static final UUID FIRST_ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private ConversationContextRepository repository;

    private ConversationContextService service;

    @BeforeEach
    void setUp() {
        service = new ConversationContextService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void snapshotsAreSeparatedByActorAndChannelInsideOneWorkspace() {
        ConversationContext firstLine = context(WorkspaceChannel.LINE, 11L);
        ConversationContext firstRest = context(WorkspaceChannel.REST, 12L);
        ConversationContext secondLine = context(WorkspaceChannel.LINE, 21L);
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE)).thenReturn(Optional.of(firstLine));
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.REST)).thenReturn(Optional.of(firstRest));
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, SECOND_ACTOR, WorkspaceChannel.LINE)).thenReturn(Optional.of(secondLine));

        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, service::snapshot).lastTaskId())
                .isEqualTo(11L);
        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.REST, service::snapshot).lastTaskId())
                .isEqualTo(12L);
        assertThat(inScope(SECOND_ACTOR, WorkspaceChannel.LINE, service::snapshot).lastTaskId())
                .isEqualTo(21L);

        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE);
        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.REST);
        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, SECOND_ACTOR, WorkspaceChannel.LINE);
    }

    @Test
    void aNewContextCapturesTheCurrentChannelAndNeverUsesAWorkspaceSingleton() {
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannel(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE)).thenReturn(Optional.empty());
        when(repository.save(any(ConversationContext.class))).thenAnswer(call -> call.getArgument(0));

        inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> {
            service.rememberPlace(42L);
            return null;
        });

        ArgumentCaptor<ConversationContext> saved = ArgumentCaptor.forClass(ConversationContext.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getChannel()).isEqualTo(WorkspaceChannel.LINE);
        assertThat(saved.getValue().getLastPlaceId()).isEqualTo(42L);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    private static ConversationContext context(WorkspaceChannel channel, long taskId) {
        ConversationContext context = ConversationContext.create(channel, NOW);
        context.rememberTask(taskId, NOW);
        return context;
    }

    private static <T> T inScope(UUID actorId, WorkspaceChannel channel,
                                 java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, WORKSPACE_ID, channel))) {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
