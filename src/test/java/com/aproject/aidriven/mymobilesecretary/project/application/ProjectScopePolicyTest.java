package com.aproject.aidriven.mymobilesecretary.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectScopePolicyTest {

    @Test
    void authorizesAllCrudShapesOnlyForTheRevalidatedProject() {
        Fixture fixture = new Fixture();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            assertThat(fixture.policy.authorizeCreate(fixture.scope).operation())
                    .isEqualTo(ProjectCrudOperation.CREATE);
            assertThat(fixture.policy.authorizeQuery(fixture.scope).operation())
                    .isEqualTo(ProjectCrudOperation.QUERY);
            assertThat(fixture.policy
                    .authorizeUpdate(fixture.scope, fixture.projectId)
                    .operation()).isEqualTo(ProjectCrudOperation.UPDATE);
            assertThat(fixture.policy
                    .authorizeDelete(fixture.scope, fixture.projectId)
                    .operation()).isEqualTo(ProjectCrudOperation.DELETE);
        }
    }

    @Test
    void anotherProjectTargetFailsBeforeProjectRepositoryValidation() {
        Fixture fixture = new Fixture();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            assertThatThrownBy(() -> fixture.policy.authorizeUpdate(
                    fixture.scope, UUID.randomUUID()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("different Project");
        }
        verifyNoInteractions(fixture.projects);
    }

    @Test
    void crossActorOrWorkspaceScopeFailsBeforeProjectRepositoryValidation() {
        Fixture fixture = new Fixture();
        WorkspaceContext otherActor = new WorkspaceContext(
                UUID.randomUUID(), fixture.workspaceId, WorkspaceChannel.TEST);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(otherActor)) {
            assertThatThrownBy(() -> fixture.policy.authorizeQuery(fixture.scope))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("current actor");
        }
        verifyNoInteractions(fixture.projects);
    }

    @Test
    void staleVersionOrArchivedProjectFailsClosed() {
        Fixture fixture = new Fixture();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            when(fixture.project.getVersion()).thenReturn(8L);
            assertThatThrownBy(() -> fixture.policy.authorizeQuery(fixture.scope))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stale");

            when(fixture.project.getVersion()).thenReturn(7L);
            when(fixture.project.getStatus()).thenReturn(ProjectStatus.ARCHIVED);
            assertThatThrownBy(() -> fixture.policy.authorizeQuery(fixture.scope))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("archived");
        }
    }

    private static final class Fixture {
        private final UUID actorId = UUID.randomUUID();
        private final UUID workspaceId = UUID.randomUUID();
        private final UUID projectId = UUID.randomUUID();
        private final WorkspaceContext context =
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
        private final ProjectScope scope = ProjectScope.validated(
                projectId, workspaceId, actorId, ProjectType.TRAVEL, 7L);
        private final ProjectService projects = mock(ProjectService.class);
        private final Project project = mock(Project.class);
        private final ProjectScopePolicy policy = new ProjectScopePolicy(projects);

        private Fixture() {
            when(projects.lockProjectForScope(projectId)).thenReturn(project);
            when(projects.getProject(projectId)).thenReturn(project);
            when(project.getId()).thenReturn(projectId);
            when(project.getType()).thenReturn(ProjectType.TRAVEL);
            when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
            when(project.getVersion()).thenReturn(7L);
        }
    }
}
