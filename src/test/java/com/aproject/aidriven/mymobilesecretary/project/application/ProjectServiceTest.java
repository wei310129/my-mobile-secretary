package com.aproject.aidriven.mymobilesecretary.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2030-04-01T00:00:00Z");
    private static final String REQUEST_HMAC = "d".repeat(64);

    @Mock private ProjectRepository projects;
    @Mock private ApplicationEventPublisher events;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projects, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createUsesDatabaseArbitrationAndPublishesOneLifecycleEvent() {
        Project persisted = Project.create(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC, NOW);
        when(projects.findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
                WORKSPACE_ID, ACTOR_ID, REQUEST_HMAC))
                .thenReturn(Optional.empty(), Optional.of(persisted));
        when(projects.insertIfAbsent(any(UUID.class), eq("TRAVEL"), eq("大阪旅行"),
                eq(REQUEST_HMAC), eq(NOW), eq(WORKSPACE_ID), eq(ACTOR_ID))).thenReturn(1);

        Project created = inContext(() ->
                service.createProject(ProjectType.TRAVEL, " 大阪旅行 ", REQUEST_HMAC));

        assertThat(created).isSameAs(persisted);
        verify(events).publishEvent(any(ProjectLifecycleEvent.class));
    }

    @Test
    void exactReplayReturnsExistingWithoutASecondInsertOrEvent() {
        Project existing = Project.create(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC, NOW);
        when(projects.findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
                WORKSPACE_ID, ACTOR_ID, REQUEST_HMAC)).thenReturn(Optional.of(existing));

        Project replayed = inContext(() ->
                service.createProject(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC));

        assertThat(replayed).isSameAs(existing);
        verify(projects, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(),
                any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void sameRequestKeyWithDifferentPayloadFailsWithoutMutation() {
        Project existing = Project.create(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC, NOW);
        when(projects.findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
                WORKSPACE_ID, ACTOR_ID, REQUEST_HMAC)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> inContext(() ->
                service.createProject(ProjectType.TRAVEL, "北海道旅行", REQUEST_HMAC)))
                .hasMessageContaining("different project payload");

        verify(projects, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(),
                any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void displayProjectionDoesNotExposeTheProjectIdentifier() {
        Project project = Project.create(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC, NOW);
        when(projects.findByIdAndWorkspaceIdAndCreatedByUserId(
                project.getId(), WORKSPACE_ID, ACTOR_ID)).thenReturn(Optional.of(project));

        ProjectDisplay display = inContext(() -> service.display(project.getId()));

        assertThat(display.name()).isEqualTo("大阪旅行");
        assertThat(display.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(display.toString()).doesNotContain(project.getId().toString());
    }

    @Test
    void lifecycleMutationsPublishTypedEventsInOrder() {
        Project project = Project.create(ProjectType.TRAVEL, "大阪旅行", REQUEST_HMAC, NOW);
        when(projects.findByIdAndWorkspaceIdAndCreatedByUserId(
                project.getId(), WORKSPACE_ID, ACTOR_ID)).thenReturn(Optional.of(project));

        inContext(() -> service.completeProject(project.getId()));
        inContext(() -> service.reopenProject(project.getId()));
        inContext(() -> service.renameProject(project.getId(), "大阪與京都"));
        inContext(() -> service.archiveProject(project.getId()));

        ArgumentCaptor<ProjectLifecycleEvent> captured =
                ArgumentCaptor.forClass(ProjectLifecycleEvent.class);
        verify(events, times(4)).publishEvent(captured.capture());
        assertThat(captured.getAllValues())
                .extracting(ProjectLifecycleEvent::action)
                .containsExactly(ProjectLifecycleEvent.Action.COMPLETED,
                        ProjectLifecycleEvent.Action.REOPENED,
                        ProjectLifecycleEvent.Action.RENAMED,
                        ProjectLifecycleEvent.Action.ARCHIVED);
    }

    private <T> T inContext(java.util.concurrent.Callable<T> action) {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            try {
                return action.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
