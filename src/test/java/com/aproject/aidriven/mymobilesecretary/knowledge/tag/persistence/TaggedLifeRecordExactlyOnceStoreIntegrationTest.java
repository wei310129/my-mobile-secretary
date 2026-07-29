package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TaggedLifeRecordExactlyOnceStoreIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_life_record_exactly_once_runtime";
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-25T08:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-07-25T08:00:01Z");
    private static final String EVENT_KEY_HASH = "a".repeat(64);
    private static final String PAYLOAD_HASH = "b".repeat(64);

    @Autowired private TaggedLifeRecordExactlyOnceStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname =
                            'mms_life_record_exactly_once_runtime') THEN
                        CREATE ROLE mms_life_record_exactly_once_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON tagged_life_record TO "
                        + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT USAGE, SELECT ON SEQUENCE tagged_life_record_id_seq TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void sequentialReplayReturnsTheOriginalRecord() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "sequential owner");
        WorkspaceContext context = context(actor, workspace);

        var first = inContext(context, () -> insert(PAYLOAD_HASH));
        var replay = inContext(context, () -> insert(PAYLOAD_HASH));

        assertThat(first.inserted()).isTrue();
        assertThat(replay.inserted()).isFalse();
        assertThat(replay.recordId()).isEqualTo(first.recordId());
        assertThat(count(actor, workspace)).isEqualTo(1L);
    }

    @Test
    void concurrentReplayProducesOneRecordAndOneWinner() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "concurrent owner");
        WorkspaceContext context = context(actor, workspace);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TaggedLifeRecordExactlyOnceStore.RecordResult> first =
                    pool.submit(() -> concurrentInsert(context, ready, start));
            Future<TaggedLifeRecordExactlyOnceStore.RecordResult> second =
                    pool.submit(() -> concurrentInsert(context, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var firstResult = first.get(30, TimeUnit.SECONDS);
            var secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.recordId()).isEqualTo(secondResult.recordId());
            assertThat(List.of(firstResult.inserted(), secondResult.inserted()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(count(actor, workspace)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameEventIdentityWithDifferentPayloadFailsClosed() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "conflict owner");
        WorkspaceContext context = context(actor, workspace);
        inContext(context, () -> insert(PAYLOAD_HASH));

        assertThatThrownBy(() -> inContext(context, () -> insert("c".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("LIFE_RECORD_EVENT_IDENTITY_CONFLICT");
        assertThat(count(actor, workspace)).isEqualTo(1L);
    }

    @Test
    void eventIdentityIsScopedByActorAndWorkspace() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seed(owner, workspace, "isolation owner");
        seedPeer(peer, owner, workspace, "isolation peer");
        seed(outsider, otherWorkspace, "isolation outsider");

        var ownerRecord = inContext(context(owner, workspace), () -> insert(PAYLOAD_HASH));
        var peerRecord = inContext(context(peer, workspace), () -> insert(PAYLOAD_HASH));
        var outsiderRecord =
                inContext(context(outsider, otherWorkspace), () -> insert(PAYLOAD_HASH));

        assertThat(ownerRecord.inserted()).isTrue();
        assertThat(peerRecord.inserted()).isTrue();
        assertThat(outsiderRecord.inserted()).isTrue();
        assertThat(
                        java.util.Set.of(
                                ownerRecord.recordId(),
                                peerRecord.recordId(),
                                outsiderRecord.recordId()))
                .hasSize(3);
        assertThat(count(owner, workspace)).isEqualTo(1L);
        assertThat(count(peer, workspace)).isEqualTo(1L);
        assertThat(count(outsider, otherWorkspace)).isEqualTo(1L);
    }

    @Test
    void runtimeNoBypassRlsAllowsOnlyTheScopedActor() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "runtime owner");
        seedPeer(peer, owner, workspace, "runtime peer");
        WorkspaceContext ownerContext = context(owner, workspace);
        WorkspaceContext peerContext = context(peer, workspace);

        var ownerRecord = runtime(ownerContext, () -> insert(PAYLOAD_HASH));

        assertThat(ownerRecord.inserted()).isTrue();
        assertThat(runtime(ownerContext, this::visibleCount)).isEqualTo(1L);
        assertThat(runtime(peerContext, this::visibleCount)).isZero();
        assertThat(runtime(WorkspaceContext.system(), this::visibleCount)).isZero();

        var peerRecord = runtime(peerContext, () -> insert(PAYLOAD_HASH));

        assertThat(peerRecord.inserted()).isTrue();
        assertThat(peerRecord.recordId()).isNotEqualTo(ownerRecord.recordId());
        assertThat(runtime(peerContext, this::visibleCount)).isEqualTo(1L);
    }

    private TaggedLifeRecordExactlyOnceStore.RecordResult insert(String payloadHash) {
        return store.insertOrVerify(
                TaggedLifeRecord.RecordType.SCHEDULE,
                "Calendar lifecycle",
                OCCURRED_AT,
                "CalendarPlan canceled",
                CREATED_AT,
                EVENT_KEY_HASH,
                payloadHash);
    }

    private TaggedLifeRecordExactlyOnceStore.RecordResult concurrentInsert(
            WorkspaceContext context, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return inContext(context, () -> insert(PAYLOAD_HASH));
    }

    private long count(UUID actor, UUID workspace) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM tagged_life_record
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND source_event_key_hash = ?
                """,
                Long.class,
                workspace,
                actor,
                EVENT_KEY_HASH);
    }

    private long visibleCount() {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM tagged_life_record
                WHERE source_event_key_hash = ?
                """,
                Long.class,
                EVENT_KEY_HASH);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id,
                    created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedPeer(UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        seedUser(actorId, label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role,
                    created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
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
            return new TransactionTemplate(transactions)
                    .execute(
                            status -> {
                                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                                return work.get();
                            });
        }
    }
}
