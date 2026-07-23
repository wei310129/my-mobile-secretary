package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusDirective;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectConversationFocusBindingFactory;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScopeFromFocusService;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectIntentHandlerTest {

    @Test
    void uniqueProjectSelectorProducesTypedWorkflowCandidateWithoutPublicId() {
        ProjectService projects = mock(ProjectService.class);
        ProjectScopeFromFocusService scopes = mock(ProjectScopeFromFocusService.class);
        Project project = project("大阪親子旅遊", ProjectStatus.ACTIVE);
        when(projects.listProjects()).thenReturn(List.of(project));
        ProjectIntentHandler handler = new ProjectIntentHandler(
                projects, scopes, new ProjectConversationFocusBindingFactory());

        IntentResult result = handler.handle("進大阪那個",
                command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "大阪"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PROJECT_MODE_INFO);
        assertThat(result.focusBinding().workflowId()).isEqualTo(project.getId());
        assertThat(result.focusBinding().domain()).isEqualTo("PROJECT");
        assertThat(result.message()).contains("大阪親子旅遊")
                .doesNotContain(project.getId().toString());
    }

    @Test
    void duplicateProjectNamesClarifyWithoutFocusCandidateOrMutation() {
        ProjectService projects = mock(ProjectService.class);
        ProjectScopeFromFocusService scopes = mock(ProjectScopeFromFocusService.class);
        Project active = project("大阪親子旅遊", ProjectStatus.ACTIVE);
        Project completed = project("大阪親子旅遊", ProjectStatus.COMPLETED);
        when(projects.listProjects()).thenReturn(List.of(active, completed));
        ProjectIntentHandler handler = new ProjectIntentHandler(
                projects, scopes, new ProjectConversationFocusBindingFactory());

        IntentResult result = handler.handle("打開大阪親子旅遊",
                command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "大阪親子旅遊"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.focusBinding()).isNull();
        assertThat(result.focusDirective()).isNull();
        verifyNoInteractions(scopes);
    }

    @Test
    void closeModeDelegatesOnlyToGlobalExitAndNamedCloseClarifies() {
        ProjectIntentHandler handler = new ProjectIntentHandler(
                mock(ProjectService.class), mock(ProjectScopeFromFocusService.class),
                new ProjectConversationFocusBindingFactory());

        IntentResult closeMode = handler.handle("先關掉專案模式",
                command(IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE, null));
        IntentResult ambiguous = handler.handle("關掉日本旅行",
                command(IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE, "日本旅行"));

        assertThat(closeMode.focusDirective())
                .isEqualTo(ConversationFocusDirective.FOCUS_CONTROL_ONLY);
        assertThat(closeMode.message()).contains("不會完成、封存或刪除");
        assertThat(ambiguous.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(ambiguous.focusDirective()).isNull();
    }

    private static Project project(String name, ProjectStatus status) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(UUID.randomUUID());
        when(project.getName()).thenReturn(name);
        when(project.getStatus()).thenReturn(status);
        return project;
    }

    private static IntentCommand command(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
