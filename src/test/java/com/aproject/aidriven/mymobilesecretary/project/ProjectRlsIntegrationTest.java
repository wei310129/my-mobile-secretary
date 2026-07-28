package com.aproject.aidriven.mymobilesecretary.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProjectRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_rls_test_runtime";

    @Autowired private ProjectService service;
    @Autowired private ProjectRepository projects;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mms_rls_test_runtime') THEN
                        CREATE ROLE mms_rls_test_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + RUNTIME_ROLE);
    }

    @Test
    void projectLifecycleIsIdempotentActorScopedAndRecordedAsLifeEvents() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "owner");
        WorkspaceContext context = context(actor, workspace);

        Project created = inContext(context, () ->
                service.createProject(ProjectType.TRAVEL, "大阪家庭旅行", "a".repeat(64)));
        Project replayed = inContext(context, () ->
                service.createProject(ProjectType.TRAVEL, "大阪家庭旅行", "a".repeat(64)));

        assertThat(replayed.getId()).isEqualTo(created.getId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE workspace_id = ? AND created_by_user_id = ?",
                Long.class, workspace, actor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM tagged_life_record
                WHERE workspace_id = ? AND created_by_user_id = ?
                    AND record_type = 'PROJECT' AND title = '大阪家庭旅行'
                """, Long.class, workspace, actor)).isEqualTo(1L);

        inContext(context, () -> service.completeProject(created.getId()));
        assertThat(inContext(context, () -> service.getProject(created.getId())).getStatus())
                .isEqualTo(ProjectStatus.COMPLETED);
        assertThatThrownBy(() -> inContext(context,
                () -> service.completeProject(created.getId()))).hasMessageContaining("COMPLETED");

        inContext(context, () -> service.reopenProject(created.getId()));
        inContext(context, () -> service.renameProject(created.getId(), "大阪與京都"));
        inContext(context, () -> service.archiveProject(created.getId()));
        assertThat(inContext(context, () -> service.getProject(created.getId())).getStatus())
                .isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void runtimeRoleAndApplicationFilterHideOtherActorAndWorkspaceProjects() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID peerActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first");
        seed(secondActor, secondWorkspace, "second");
        seedPeer(peerActor, firstActor, firstWorkspace, "peer");
        Project first = inContext(context(firstActor, firstWorkspace), () ->
                service.createProject(ProjectType.TRAVEL, "第一個旅行", "b".repeat(64)));
        Project second = inContext(context(secondActor, secondWorkspace), () ->
                service.createProject(ProjectType.TRAVEL, "第二個旅行", "c".repeat(64)));
        inContext(context(peerActor, firstWorkspace), () ->
                service.createProject(ProjectType.TRAVEL, "同 workspace 的私人旅行", "f".repeat(64)));

        List<String> firstVisible = runtime(context(firstActor, firstWorkspace),
                () -> jdbc.queryForList("SELECT name FROM project", String.class));
        assertThat(firstVisible).containsExactly("第一個旅行");
        assertThat(runtime(context(firstActor, firstWorkspace),
                () -> jdbc.update("UPDATE project SET name = '越權修改' WHERE id = ?", second.getId())))
                .isZero();
        assertThat(runtime(context(firstActor, firstWorkspace),
                () -> jdbc.update("DELETE FROM project WHERE id = ?", second.getId())))
                .isZero();
        assertThatThrownBy(() -> runtime(context(firstActor, firstWorkspace), () -> jdbc.update("""
                INSERT INTO project (
                    id, project_type, name, status, creation_request_hmac, version,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'TRAVEL', '越權建立', 'ACTIVE', ?, 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), "0".repeat(64), secondWorkspace, secondActor)))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> inContext(context(secondActor, secondWorkspace),
                () -> service.getProject(first.getId()))).hasMessageContaining("requested project");
        assertThat(runtime(context(secondActor, secondWorkspace), service::listProjects))
                .extracting(Project::getName).containsExactly("第二個旅行");
    }

    @Test
    void concurrentCreationWithOneRequestHmacReturnsOneProjectAndOneLifeEvent() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "concurrent owner");
        WorkspaceContext context = context(actor, workspace);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = pool.submit(() ->
                    concurrentCreate(context, ready, start, "d".repeat(64)));
            Future<UUID> second = pool.submit(() ->
                    concurrentCreate(context, ready, start, "d".repeat(64)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM project
                    WHERE workspace_id = ? AND created_by_user_id = ?
                    """, Long.class, workspace, actor)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM tagged_life_record
                    WHERE workspace_id = ? AND created_by_user_id = ?
                        AND record_type = 'PROJECT'
                    """, Long.class, workspace, actor)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void optimisticVersionAllowsOnlyOneConcurrentRenameToCommit() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "version owner");
        WorkspaceContext context = context(actor, workspace);
        Project project = inContext(context, () ->
                service.createProject(ProjectType.TRAVEL, "原始名稱", "e".repeat(64)));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch mutate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() ->
                    concurrentRename(context, project.getId(), "名稱甲", loaded, mutate));
            Future<Boolean> second = pool.submit(() ->
                    concurrentRename(context, project.getId(), "名稱乙", loaded, mutate));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            mutate.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            Project persisted = inContext(context, () -> service.getProject(project.getId()));
            assertThat(persisted.getName()).isIn("名稱甲", "名稱乙");
            assertThat(persisted.getVersion()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
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

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
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

    private UUID concurrentCreate(WorkspaceContext context, CountDownLatch ready,
                                  CountDownLatch start, String requestHmac) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return inContext(context, () ->
                service.createProject(ProjectType.TRAVEL, "同一趟旅行", requestHmac).getId());
    }

    private boolean concurrentRename(WorkspaceContext context, UUID projectId, String name,
                                     CountDownLatch loaded, CountDownLatch mutate) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                Project project = projects
                        .findByIdAndWorkspaceIdAndCreatedByUserId(
                                projectId, context.workspaceId(), context.actorId())
                        .orElseThrow();
                loaded.countDown();
                try {
                    if (!mutate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent rename did not start");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                project.rename(name, java.time.Instant.now());
                projects.saveAndFlush(project);
                return true;
            }));
        } catch (RuntimeException expectedConflict) {
            return false;
        }
    }
}
