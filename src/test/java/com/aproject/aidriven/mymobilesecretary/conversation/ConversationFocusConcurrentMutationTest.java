package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusConcurrentMutationTest extends IntegrationTestBase {

    @Autowired private ConversationFocusService service;
    @Autowired private ConversationScopeResolver resolver;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentEnterCannotLastWriteWinsAndLeavesOneActiveFocusAndTransition() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", "concurrent-scope");
        ConversationScopeKey scope = resolver.current(context);
        seedAccount(actorId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO conversation_focus_head (
                    id, channel, conversation_scope_digest, scope_key_version, revision, version,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), scope.digest(), scope.keyVersion(), workspaceId, actorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(enter(context, ready, start, "a".repeat(64)));
            Future<Boolean> second = executor.submit(enter(context, ready, start, "b".repeat(64)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_focus
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'ACTIVE'
                """, Long.class, workspaceId, actorId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM focus_transition
                WHERE workspace_id = ? AND created_by_user_id = ? AND type = 'ENTER'
                """, Long.class, workspaceId, actorId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT revision FROM conversation_focus_head
                WHERE workspace_id = ? AND created_by_user_id = ? AND conversation_scope_digest = ?
                """, Long.class, workspaceId, actorId, scope.digest())).isEqualTo(1L);
    }

    @Test
    void concurrentResumeOfOneSuspendedFocusHasOneWinnerAndOneResumeTransition() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", "resume-scope");
        ConversationScopeKey scope = resolver.current(context);
        UUID focusId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO conversation_focus_head (
                    id, channel, conversation_scope_digest, scope_key_version, revision, version,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), scope.digest(), scope.keyVersion(), workspaceId, actorId);
        jdbcTemplate.update("""
                INSERT INTO conversation_focus (
                    id, channel, conversation_scope_digest, scope_key_version, root_kind, root_domain,
                    workflow_id, safe_label, status, version, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, ?, 'WORKFLOW', 'PROJECT', ?, '可恢復旅行', 'SUSPENDED', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, focusId, scope.digest(), scope.keyVersion(), UUID.randomUUID(), workspaceId, actorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(resume(context, focusId, ready, start, "c".repeat(64)));
            Future<Boolean> second = executor.submit(resume(context, focusId, ready, start, "d".repeat(64)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM conversation_focus WHERE id = ?
                """, String.class, focusId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM focus_transition
                WHERE workspace_id = ? AND created_by_user_id = ? AND type = 'RESUME'
                """, Long.class, workspaceId, actorId)).isEqualTo(1L);
    }

    @Test
    void servicePersistsEachLegalTransitionOnceWhileKeepLeavesRevisionUntouched() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", "matrix-scope");
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            UUID firstWorkflow = UUID.randomUUID();
            UUID firstFocus = service.enterWorkflow("PROJECT", firstWorkflow, "東京旅行", "0".repeat(64))
                    .getId();
            assertThat(revision(workspaceId, actorId)).isEqualTo(1L);
            service.keep();
            assertThat(revision(workspaceId, actorId)).isEqualTo(1L);
            service.changeActivity("FLIGHT", "回程班機", "1".repeat(64));
            service.exit("2".repeat(64));
            service.resume(firstFocus, "3".repeat(64));
            service.switchWorkflow("PROJECT", UUID.randomUUID(), "大阪旅行", "4".repeat(64));
            service.close(com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason.USER_CLOSED,
                    "5".repeat(64));
            service.enterWorkflow("PROJECT", UUID.randomUUID(), "北海道旅行", "6".repeat(64));
            service.invalidate("7".repeat(64));
        }

        assertThat(revision(workspaceId, actorId)).isEqualTo(8L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT type FROM focus_transition
                WHERE workspace_id = ? AND created_by_user_id = ? ORDER BY before_revision
                """, String.class, workspaceId, actorId))
                .containsExactly("ENTER", "CHANGE_SUBFOCUS", "EXIT", "RESUME", "SWITCH", "CLOSE",
                        "ENTER", "INVALIDATE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_focus
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'ACTIVE'
                """, Long.class, workspaceId, actorId)).isZero();
    }

    private Callable<Boolean> enter(WorkspaceContext context, CountDownLatch ready, CountDownLatch start,
                                    String inboundHmac) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                service.enterWorkflow("PROJECT", UUID.randomUUID(), "並行旅行", inboundHmac);
                return true;
            } catch (IllegalStateException expectedConflict) {
                return false;
            }
        };
    }

    private Callable<Boolean> resume(WorkspaceContext context, UUID focusId, CountDownLatch ready,
                                     CountDownLatch start, String inboundHmac) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                service.resume(focusId, inboundHmac);
                return true;
            } catch (RuntimeException expectedConflict) {
                return false;
            }
        };
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Focus concurrent user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Focus concurrent workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }

    private long revision(UUID workspaceId, UUID actorId) {
        return jdbcTemplate.queryForObject("""
                SELECT revision FROM conversation_focus_head
                WHERE workspace_id = ? AND created_by_user_id = ?
                """, Long.class, workspaceId, actorId);
    }
}
