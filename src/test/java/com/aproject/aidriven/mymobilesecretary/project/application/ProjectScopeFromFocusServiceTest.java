package com.aproject.aidriven.mymobilesecretary.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectConversationFocusBindingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectScopeFromFocusServiceTest {

    @Test
    void revalidatesTypedBindingAndProjectBeforeCreatingScope() {
        Fixture fixture = new Fixture();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            ProjectScope scope = fixture.service.requireActive();

            assertThat(scope.projectId()).isEqualTo(fixture.projectId);
            assertThat(scope.workspaceId()).isEqualTo(fixture.workspaceId);
            assertThat(scope.actorId()).isEqualTo(fixture.actorId);
            assertThat(scope.type()).isEqualTo(ProjectType.TRAVEL);
            assertThat(scope.projectVersion()).isEqualTo(3L);
        }
    }

    @Test
    void focusRowAloneNeverAuthorizesProjectScope() {
        Fixture fixture = new Fixture();
        when(fixture.bindings
                .findByConversationFocusIdAndWorkspaceIdAndCreatedByUserId(
                        fixture.focusId, fixture.workspaceId, fixture.actorId))
                .thenReturn(Optional.empty());

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            assertThatThrownBy(fixture.service::requireActive)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("typed Project focus binding");
        }
    }

    @Test
    void archivedProjectCannotProduceScope() {
        Fixture fixture = new Fixture();
        when(fixture.project.getStatus()).thenReturn(ProjectStatus.ARCHIVED);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context)) {
            assertThatThrownBy(fixture.service::requireActive)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("archived");
        }
    }

    private static final class Fixture {
        private final UUID actorId = UUID.randomUUID();
        private final UUID workspaceId = UUID.randomUUID();
        private final UUID focusId = UUID.randomUUID();
        private final UUID projectId = UUID.randomUUID();
        private final WorkspaceContext context =
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
        private final ConversationFocusService focuses = mock(ConversationFocusService.class);
        private final ProjectConversationFocusBindingRepository bindings =
                mock(ProjectConversationFocusBindingRepository.class);
        private final ProjectService projects = mock(ProjectService.class);
        private final ConversationFocus focus = mock(ConversationFocus.class);
        private final ProjectConversationFocusBinding binding =
                mock(ProjectConversationFocusBinding.class);
        private final Project project = mock(Project.class);
        private final ProjectScopeFromFocusService service =
                new ProjectScopeFromFocusService(focuses, bindings, projects);

        private Fixture() {
            when(focuses.activeFocus()).thenReturn(Optional.of(focus));
            when(focus.getId()).thenReturn(focusId);
            when(focus.getRootDomain()).thenReturn("PROJECT");
            when(focus.getWorkflowId()).thenReturn(projectId);
            when(bindings.findByConversationFocusIdAndWorkspaceIdAndCreatedByUserId(
                    focusId, workspaceId, actorId)).thenReturn(Optional.of(binding));
            when(binding.getProjectId()).thenReturn(projectId);
            when(projects.getProject(projectId)).thenReturn(project);
            when(project.getId()).thenReturn(projectId);
            when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
            when(project.getType()).thenReturn(ProjectType.TRAVEL);
            when(project.getVersion()).thenReturn(3L);
        }
    }
}
