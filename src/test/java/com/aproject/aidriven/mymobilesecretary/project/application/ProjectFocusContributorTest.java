package com.aproject.aidriven.mymobilesecretary.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTargetResolver;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFocusContributorTest {

    @Test
    void acceptsOnlyAnAuthorizedNonArchivedProjectWorkflow() {
        ProjectService projects = mock(ProjectService.class);
        Project project = mock(Project.class);
        UUID projectId = UUID.randomUUID();
        when(projects.getProject(projectId)).thenReturn(project);
        when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
        ProjectFocusContributor contributor = new ProjectFocusContributor(projects);

        assertThat(contributor.isAvailable(workflow(projectId))).isTrue();

        when(project.getStatus()).thenReturn(ProjectStatus.ARCHIVED);
        assertThat(contributor.isAvailable(workflow(projectId))).isFalse();
        assertThat(contributor.isAvailable(new ConversationFocusTargetResolver.ResourceTarget(
                "PROJECT", "project:" + projectId, "大阪旅行"))).isFalse();
    }

    @Test
    void inaccessibleProjectFailsClosed() {
        ProjectService projects = mock(ProjectService.class);
        UUID projectId = UUID.randomUUID();
        when(projects.getProject(projectId)).thenThrow(new RuntimeException("not authorized"));

        assertThat(new ProjectFocusContributor(projects).isAvailable(workflow(projectId))).isFalse();
    }

    private static ConversationFocusTargetResolver.ResourceTarget workflow(UUID projectId) {
        return new ConversationFocusTargetResolver.ResourceTarget(
                "PROJECT", null, projectId, "大阪旅行", null, null);
    }
}
