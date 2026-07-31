package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeProperties;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
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
    private ConversationScopeResolver scopeResolver;

    @BeforeEach
    void setUp() {
        scopeResolver = new ConversationScopeResolver(
                new ConversationScopeProperties(1, "dGVzdC1jb252ZXJzYXRpb24tc2NvcGUtaG1hYy1rZXk=", null, null));
        service = new ConversationContextService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                scopeResolver);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void snapshotsAreSeparatedByActorAndChannelInsideOneWorkspace() {
        ConversationContext firstLine = context(FIRST_ACTOR, WorkspaceChannel.LINE, 11L);
        ConversationContext firstRest = context(FIRST_ACTOR, WorkspaceChannel.REST, 12L);
        ConversationContext secondLine = context(SECOND_ACTOR, WorkspaceChannel.LINE, 21L);
        stubContext(FIRST_ACTOR, WorkspaceChannel.LINE, firstLine);
        stubContext(FIRST_ACTOR, WorkspaceChannel.REST, firstRest);
        stubContext(SECOND_ACTOR, WorkspaceChannel.LINE, secondLine);

        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, service::snapshot).lastTaskId())
                .isEqualTo(11L);
        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.REST, service::snapshot).lastTaskId())
                .isEqualTo(12L);
        assertThat(inScope(SECOND_ACTOR, WorkspaceChannel.LINE, service::snapshot).lastTaskId())
                .isEqualTo(21L);

        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE,
                scopeResolver.current(contextOf(FIRST_ACTOR, WorkspaceChannel.LINE)).digest());
        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.REST,
                scopeResolver.current(contextOf(FIRST_ACTOR, WorkspaceChannel.REST)).digest());
        verify(repository).findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, SECOND_ACTOR, WorkspaceChannel.LINE,
                scopeResolver.current(contextOf(SECOND_ACTOR, WorkspaceChannel.LINE)).digest());
    }

    @Test
    void snapshotsAreSeparatedByTrustedConversationScopeInsideOneLineChannel() {
        ConversationScopeResolver scopeResolver = new ConversationScopeResolver(
                new ConversationScopeProperties(1, "dGVzdC1jb252ZXJzYXRpb24tc2NvcGUtaG1hYy1rZXk=", null, null));
        WorkspaceContext group = new WorkspaceContext(FIRST_ACTOR, WORKSPACE_ID,
                WorkspaceChannel.LINE, "line", "group:line-group-a");
        WorkspaceContext room = new WorkspaceContext(FIRST_ACTOR, WORKSPACE_ID,
                WorkspaceChannel.LINE, "line", "room:line-room-b");
        ConversationScopeKey groupScope = scopeResolver.current(group);
        ConversationScopeKey roomScope = scopeResolver.current(room);
        ConversationContext groupContext = ConversationContext.create(
                WorkspaceChannel.LINE, groupScope, NOW);
        ConversationContext roomContext = ConversationContext.create(
                WorkspaceChannel.LINE, roomScope, NOW);
        groupContext.rememberTask(11L, NOW);
        roomContext.rememberTask(22L, NOW);
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE, groupScope.digest()))
                .thenReturn(Optional.of(groupContext));
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE, roomScope.digest()))
                .thenReturn(Optional.of(roomContext));

        assertThat(inScope(group, service::snapshot).lastTaskId()).isEqualTo(11L);
        assertThat(inScope(room, service::snapshot).lastTaskId()).isEqualTo(22L);
    }

    @Test
    void previousKeyContextMigratesUnderLockAndUnknownPersistedVersionFailsClosed() {
        WorkspaceContext context = new WorkspaceContext(FIRST_ACTOR, WORKSPACE_ID,
                WorkspaceChannel.LINE, "line", "group:rotation");
        ConversationScopeResolver oldResolver = new ConversationScopeResolver(
                new ConversationScopeProperties(1, "b2xkLWNvbnZlcnNhdGlvbi1zY29wZS1rZXk=", null, null));
        ConversationScopeResolver rotatedResolver = new ConversationScopeResolver(
                new ConversationScopeProperties(2, "bmV3LWNvbnZlcnNhdGlvbi1zY29wZS1rZXk=", 1,
                        "b2xkLWNvbnZlcnNhdGlvbi1zY29wZS1rZXk="));
        ConversationScopeKey oldScope = oldResolver.current(context);
        ConversationScopeKey newScope = rotatedResolver.current(context);
        ConversationContext oldContext = ConversationContext.create(WorkspaceChannel.LINE, oldScope, NOW);
        oldContext.rememberTask(31L, NOW);
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE, newScope.digest()))
                .thenReturn(Optional.empty());
        when(repository.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE, oldScope.digest()))
                .thenReturn(Optional.of(oldContext));
        ConversationContextService rotatedService = new ConversationContextService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), rotatedResolver);

        assertThat(inScope(context, rotatedService::snapshot).lastTaskId()).isEqualTo(31L);
        assertThat(oldContext.getConversationScopeDigest()).isEqualTo(newScope.digest());
        assertThat(oldContext.getScopeKeyVersion()).isEqualTo(2);

        oldContext.migrateScope(new ConversationScopeKey(newScope.digest(), 99), NOW);
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE, newScope.digest()))
                .thenReturn(Optional.of(oldContext));

        assertThatThrownBy(() -> inScope(context, rotatedService::snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not recognized");
    }

    @Test
    void aNewContextCapturesTheCurrentChannelAndNeverUsesAWorkspaceSingleton() {
        WorkspaceContext context = contextOf(FIRST_ACTOR, WorkspaceChannel.LINE);
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, FIRST_ACTOR, WorkspaceChannel.LINE,
                scopeResolver.current(context).digest())).thenReturn(Optional.empty());
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

    @Test
    void unrelatedExchangeClearsPreviouslyAdvertisedTaskAndScheduleOrdinals() {
        ConversationContext context = context(FIRST_ACTOR, WorkspaceChannel.LINE, 11L);
        context.rememberTaskList("11,22", NOW);
        context.rememberScheduleList("31,32", NOW);
        context.rememberExchange("AGENDA_LISTED", "列出今天行程", "1. A 2. B", NOW);
        stubContext(FIRST_ACTOR, WorkspaceChannel.LINE, context);

        inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> {
            service.rememberExchange("你現在能做到什麼？",
                    IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "請選一類功能"));
            return null;
        });

        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> service.taskIdAt(2))).isNull();
        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> service.scheduleIdAt(2))).isNull();
    }

    @Test
    void listsRefreshedInTheCurrentExchangeRemainSelectable() {
        ConversationContext context = context(FIRST_ACTOR, WorkspaceChannel.LINE, 11L);
        context.rememberTaskList("41,42", NOW);
        context.rememberScheduleList("51,52", NOW);
        stubContext(FIRST_ACTOR, WorkspaceChannel.LINE, context);

        inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> {
            service.rememberExchange("列出今天的事項",
                    IntentResult.message(IntentResult.Action.AGENDA_LISTED, "1. 任務 2. 行程"));
            return null;
        });

        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> service.taskIdAt(2)))
                .isEqualTo(42L);
        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> service.scheduleIdAt(2)))
                .isEqualTo(52L);
    }

    @Test
    void objectAnnotationCandidatesAndPendingDeleteExpireOnAnUnrelatedExchange() {
        ConversationContext context = context(FIRST_ACTOR, WorkspaceChannel.LINE, 11L);
        context.rememberObjectAnnotationList("61,62", NOW);
        context.prepareObjectAnnotationDelete(62L, NOW);
        context.rememberExchange("ANNOTATIONS_LISTED", "列出標記", "1. A 2. B", NOW);
        stubContext(FIRST_ACTOR, WorkspaceChannel.LINE, context);

        inScope(FIRST_ACTOR, WorkspaceChannel.LINE, () -> {
            service.rememberExchange("謝謝",
                    IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "不客氣"));
            return null;
        });

        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE,
                () -> service.objectAnnotationIdAt(2))).isNull();
        assertThat(inScope(FIRST_ACTOR, WorkspaceChannel.LINE,
                service::pendingObjectAnnotationDeleteId)).isNull();
    }

    private ConversationContext context(UUID actorId, WorkspaceChannel channel, long taskId) {
        ConversationContext context = ConversationContext.create(channel,
                scopeResolver.current(contextOf(actorId, channel)), NOW);
        context.rememberTask(taskId, NOW);
        return context;
    }

    private void stubContext(UUID actorId, WorkspaceChannel channel, ConversationContext context) {
        when(repository.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
                WORKSPACE_ID, actorId, channel,
                scopeResolver.current(contextOf(actorId, channel)).digest())).thenReturn(Optional.of(context));
    }

    private static WorkspaceContext contextOf(UUID actorId, WorkspaceChannel channel) {
        return new WorkspaceContext(actorId, WORKSPACE_ID, channel);
    }

    private static <T> T inScope(UUID actorId, WorkspaceChannel channel,
                                 java.util.concurrent.Callable<T> action) {
        return inScope(new WorkspaceContext(actorId, WORKSPACE_ID, channel), action);
    }

    private static <T> T inScope(WorkspaceContext context,
                                 java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context)) {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
