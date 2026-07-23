package com.aproject.aidriven.mymobilesecretary.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusAtomicExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusControl;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusDecision;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusResponseEnvelope;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusTransitionNotice;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScope;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScopeFromFocusService;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProjectConversationFocusBindingRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_project_focus_rls_runtime";

    @Autowired private ProjectService projects;
    @Autowired private ConversationFocusAtomicExecutor focusExecutor;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationFocusQuoteResolver quoteResolver;
    @Autowired private ProjectScopeFromFocusService scopes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname = 'mms_project_focus_rls_runtime') THEN
                        CREATE ROLE mms_project_focus_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + RUNTIME_ROLE);
    }

    @Test
    void oneProjectCanBindDifferentScopesAndEachScopeMustRevalidate() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seed(actorId, workspaceId, "owner");
        WorkspaceContext first = context(actorId, workspaceId, "thread-a");
        WorkspaceContext second = context(actorId, workspaceId, "thread-b");
        Project project = inContext(first, () -> projects.createProject(
                ProjectType.TRAVEL, "大阪家庭旅行", "a".repeat(64)));

        enter(first, project, "b".repeat(64));
        ProjectScope firstScope = inContext(first, scopes::requireActive);
        enter(second, project, "c".repeat(64));
        ProjectScope secondScope = inContext(second, scopes::requireActive);

        assertThat(firstScope.projectId()).isEqualTo(project.getId());
        assertThat(secondScope.projectId()).isEqualTo(project.getId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM project_conversation_focus_binding
                WHERE project_id = ?
                """, Long.class, project.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT conversation_scope_digest)
                FROM project_conversation_focus_binding
                WHERE project_id = ?
                """, Long.class, project.getId())).isEqualTo(2L);
    }

    @Test
    void runtimeRlsAndCompositeForeignKeysRejectCrossActorBinding() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seed(ownerId, workspaceId, "owner");
        seedPeer(otherId, ownerId, workspaceId, "peer");
        WorkspaceContext owner = context(ownerId, workspaceId, "owner-thread");
        WorkspaceContext other = context(otherId, workspaceId, "other-thread");
        Project ownerProject = inContext(owner, () -> projects.createProject(
                ProjectType.TRAVEL, "owner trip", "d".repeat(64)));
        Project otherProject = inContext(other, () -> projects.createProject(
                ProjectType.TRAVEL, "other trip", "e".repeat(64)));
        enter(owner, ownerProject, "f".repeat(64));

        assertThat(runtime(owner, () -> jdbc.queryForObject(
                "SELECT count(*) FROM project_conversation_focus_binding", Long.class)))
                .isEqualTo(1L);
        assertThat(runtime(other, () -> jdbc.queryForObject(
                "SELECT count(*) FROM project_conversation_focus_binding", Long.class)))
                .isZero();
        assertThat(runtime(other, () -> jdbc.update(
                "DELETE FROM project_conversation_focus_binding WHERE project_id = ?",
                ownerProject.getId()))).isZero();

        UUID focusId = jdbc.queryForObject("""
                SELECT conversation_focus_id
                FROM project_conversation_focus_binding
                WHERE project_id = ?
                """, UUID.class, ownerProject.getId());
        String digest = jdbc.queryForObject("""
                SELECT conversation_scope_digest
                FROM project_conversation_focus_binding
                WHERE project_id = ?
                """, String.class, ownerProject.getId());
        jdbc.update(
                "DELETE FROM project_conversation_focus_binding WHERE conversation_focus_id = ?",
                focusId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_conversation_focus_binding (
                    id, conversation_focus_id, project_id, channel,
                    conversation_scope_digest, created_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, 'TEST', ?, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), focusId, otherProject.getId(), digest,
                workspaceId, ownerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sameActorDifferentChannelCannotReuseActiveProjectScope() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seed(actorId, workspaceId, "channel owner");
        WorkspaceContext testChannel = context(actorId, workspaceId, "same-token");
        WorkspaceContext restChannel = new WorkspaceContext(
                actorId, workspaceId, WorkspaceChannel.REST,
                "project-test", "same-token");
        Project project = inContext(testChannel, () -> projects.createProject(
                ProjectType.TRAVEL, "跨頻道旅行", "1".repeat(64)));
        enter(testChannel, project, "2".repeat(64));

        assertThat(inContext(testChannel, scopes::active)).isPresent();
        assertThat(inContext(restChannel, scopes::active)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_conversation_focus_binding",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void suspendedProjectQuoteIsFixedResumeButArchivedTargetFailsClosed() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seed(actorId, workspaceId, "quote owner");
        WorkspaceContext context = context(actorId, workspaceId, "quote-thread");
        Project project = inContext(context, () -> projects.createProject(
                ProjectType.TRAVEL, "葡萄牙旅行", "3".repeat(64)));
        enter(context, project, "4".repeat(64));
        UUID focusId = inContext(context, () -> focusService.activeFocus().orElseThrow().getId());
        inContext(context, () -> focusService.exit("5".repeat(64)));

        assertThat(inContext(context, () -> quoteResolver.resolveSuspendedFocus(focusId))
                .decision().type()).isEqualTo(FocusTransitionType.RESUME);

        inContext(context, () -> projects.archiveProject(project.getId()));
        assertThatThrownBy(() -> inContext(
                context, () -> quoteResolver.resolveSuspendedFocus(focusId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quoted focus target is unavailable");
    }

    private void enter(WorkspaceContext context, Project project, String inboundHmac) {
        inContext(context, () -> focusExecutor.execute(
                FocusDecision.transition(FocusTransitionType.ENTER),
                new FocusControl.EnterWorkflow("PROJECT", project.getId(), project.getName()),
                inboundHmac,
                FocusTransitionNotice.forTransition(
                        FocusTransitionType.ENTER, null, project.getName(), null),
                () -> FocusResponseEnvelope.withoutNotice("已選擇專案")));
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
        jdbc.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, label, actorId);
    }

    private void seedPeer(UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        jdbc.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
        jdbc.update("""
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), workspaceId, actorId, ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId, String scopeToken) {
        return new WorkspaceContext(
                actorId, workspaceId, WorkspaceChannel.TEST, "project-test", scopeToken);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void inContext(WorkspaceContext context, Runnable work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            work.run();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }
}
