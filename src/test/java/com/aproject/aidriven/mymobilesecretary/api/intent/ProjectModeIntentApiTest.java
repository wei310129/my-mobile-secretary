package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class ProjectModeIntentApiTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter stub;
    @Autowired private ProjectService projects;
    @Autowired private IntentService intents;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void createProjectAndEnterFocusCommitAsOneActualEntryPath() throws Exception {
        stub.nextCommand(command(IntentCommand.Type.CREATE_PROJECT, "大阪家庭旅行"));

        MvcResult response = say("幫我開一個大阪家庭旅行專案", "PROJECT_CREATED", "大阪家庭旅行");

        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContainPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(count("project")).isEqualTo(1L);
        assertThat(count("conversation_focus")).isEqualTo(1L);
        assertThat(count("project_conversation_focus_binding")).isEqualTo(1L);
        assertThat(latestTransition()).isEqualTo("ENTER");
    }

    @Test
    void closeOnlyExitsResumeIsExactCompleteKeepsAndArchiveInvalidates() throws Exception {
        Project project;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(localContext())) {
            project = projects.createProject(
                    ProjectType.TRAVEL, "大阪親子旅遊", "a".repeat(64));
        }
        stub.nextCommand(command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "大阪"));
        say("進大阪那個", "PROJECT_MODE_INFO", "大阪親子旅遊");
        UUID focusId = activeFocusId();
        assertThat(latestTransition()).isEqualTo("ENTER");

        stub.nextCommand(command(IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE, null));
        say("先關掉專案模式", "PROJECT_MODE_INFO", "不會完成、封存或刪除");
        assertThat(focusStatus(focusId)).isEqualTo("SUSPENDED");
        assertThat(projectStatus(project.getId())).isEqualTo("ACTIVE");
        assertThat(latestTransition()).isEqualTo("EXIT");

        stub.nextCommand(command(IntentCommand.Type.RESUME_PROJECT_EDIT_MODE, "大阪"));
        say("回到剛才大阪那趟", "PROJECT_MODE_INFO", "大阪親子旅遊");
        assertThat(activeFocusId()).isEqualTo(focusId);
        assertThat(latestTransition()).isEqualTo("RESUME");
        long revisionBeforeComplete = focusRevision();

        stub.nextCommand(command(IntentCommand.Type.COMPLETE_PROJECT, null));
        say("這趟完成了", "PROJECT_COMPLETED", "專案模式會保留");
        assertThat(projectStatus(project.getId())).isEqualTo("COMPLETED");
        assertThat(activeFocusId()).isEqualTo(focusId);
        assertThat(focusRevision()).isEqualTo(revisionBeforeComplete);

        stub.nextCommand(command(IntentCommand.Type.ARCHIVE_PROJECT, null));
        say("封存這個專案", "PROJECT_ARCHIVED", "大阪親子旅遊");
        assertThat(projectStatus(project.getId())).isEqualTo("ARCHIVED");
        assertThat(focusStatus(focusId)).isEqualTo("CLOSED");
        assertThat(latestTransition()).isEqualTo("INVALIDATE");
        assertThat(count("project_conversation_focus_binding")).isEqualTo(1L);
    }

    @Test
    void directArchiveLeavesFocusUntilNextTurnThenLazyInvalidatesBeforeDispatch()
            throws Exception {
        Project project;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(localContext())) {
            project = projects.createProject(
                    ProjectType.TRAVEL, "沖繩家庭旅行", "b".repeat(64));
        }
        stub.nextCommand(command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "沖繩"));
        say("打開沖繩那趟", "PROJECT_MODE_INFO", "沖繩家庭旅行");
        UUID focusId = activeFocusId();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(localContext())) {
            projects.archiveProject(project.getId());
        }
        assertThat(activeFocusId()).isEqualTo(focusId);

        stub.nextCommand(command(IntentCommand.Type.SOCIAL, null));
        say("那接著呢", "CONTEXT_UPDATED", "已封存或無法使用");

        assertThat(focusStatus(focusId)).isEqualTo("CLOSED");
        assertThat(latestTransition()).isEqualTo("INVALIDATE");
        assertThat(count("project_conversation_focus_binding")).isEqualTo(1L);
    }

    @Test
    void replayedCreateUsesOneProjectOneFocusOneBindingAndOneTransition() {
        UUID requestId = UUID.randomUUID();

        IntentResult first = createWithRequest(requestId);
        IntentResult replay = createWithRequest(requestId);

        assertThat(first.action()).isEqualTo(IntentResult.Action.PROJECT_CREATED);
        assertThat(replay.action()).isEqualTo(IntentResult.Action.PROJECT_CREATED);
        assertThat(count("project")).isEqualTo(1L);
        assertThat(count("conversation_focus")).isEqualTo(1L);
        assertThat(count("project_conversation_focus_binding")).isEqualTo(1L);
        assertThat(count("focus_transition")).isEqualTo(1L);
    }

    @Test
    void duplicateOrArchivedSelectorClarifiesWithZeroFocusMutation() throws Exception {
        Project archived;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(localContext())) {
            projects.createProject(ProjectType.TRAVEL, "大阪親子旅遊", "c".repeat(64));
            projects.createProject(ProjectType.TRAVEL, "大阪親子旅遊", "d".repeat(64));
            archived = projects.createProject(
                    ProjectType.TRAVEL, "北海道賞雪", "e".repeat(64));
            projects.archiveProject(archived.getId());
        }

        stub.nextCommand(command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "大阪親子旅遊"));
        say("打開大阪親子旅遊", "CLARIFICATION_NEEDED", "找到多個符合的專案");
        stub.nextCommand(command(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, "北海道賞雪"));
        say("打開北海道賞雪", "CLARIFICATION_NEEDED", "找不到可使用");

        assertThat(count("conversation_focus")).isZero();
        assertThat(count("focus_transition")).isZero();
        assertThat(count("project_conversation_focus_binding")).isZero();
        assertThat(projectStatus(archived.getId())).isEqualTo("ARCHIVED");
    }

    private IntentResult createWithRequest(UUID requestId) {
        stub.nextCommand(command(IntentCommand.Type.CREATE_PROJECT, "京都慢遊"));
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(localContext())) {
            return RequestCorrelationContext.run(
                    requestId, () -> intents.handle("建立京都慢遊專案", "LOCAL"));
        }
    }

    private MvcResult say(String text, String action, String messagePart) throws Exception {
        return mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "%s"}
                                """.formatted(text)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value(action))
                .andExpect(jsonPath("$.message").value(containsString(messagePart)))
                .andReturn();
    }

    private static IntentCommand command(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static WorkspaceContext localContext() {
        return new WorkspaceContext(
                LegacyAccountIds.USER_ID,
                LegacyAccountIds.WORKSPACE_ID,
                WorkspaceChannel.LOCAL);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private UUID activeFocusId() {
        return jdbc.queryForObject(
                "SELECT id FROM conversation_focus WHERE status = 'ACTIVE'",
                UUID.class);
    }

    private String focusStatus(UUID focusId) {
        return jdbc.queryForObject(
                "SELECT status FROM conversation_focus WHERE id = ?",
                String.class, focusId);
    }

    private String projectStatus(UUID projectId) {
        return jdbc.queryForObject(
                "SELECT status FROM project WHERE id = ?",
                String.class, projectId);
    }

    private String latestTransition() {
        return jdbc.queryForObject("""
                SELECT type FROM focus_transition
                ORDER BY created_at DESC, after_revision DESC
                LIMIT 1
                """, String.class);
    }

    private long focusRevision() {
        return jdbc.queryForObject(
                "SELECT revision FROM conversation_focus_head",
                Long.class);
    }
}
