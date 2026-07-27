package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.BufferRuleService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class KnowledgeMaterializationRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_knowledge_materialization_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-25T02:00:00Z");

    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private KnowledgeMaterializationService materializations;
    @Autowired private UserKnowledgeService knowledge;
    @Autowired private PlaceService places;
    @Autowired private BufferRuleService bufferRules;
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
                            'mms_knowledge_materialization_rls_runtime') THEN
                        CREATE ROLE mms_knowledge_materialization_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE"
                        + " ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void runtimeRoleIsolatesMaterializationAndBufferPolicyByActorAndWorkspace() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seed(owner, workspace, "materialization owner");
        seedPeer(peer, owner, workspace, "same workspace peer");
        seed(outsider, otherWorkspace, "other workspace actor");
        WorkspaceContext ownerContext = context(owner, workspace);

        Fixture fixture = inContext(ownerContext, this::createFixture);

        assertThat(runtime(ownerContext, this::counts)).containsExactly(1L, 1L);
        assertThat(runtime(ownerContext, () -> jdbc.update(
                        """
                        UPDATE knowledge_materialization
                        SET updated_at = updated_at
                        WHERE id = ?
                        """,
                        fixture.proposalId())))
                .isEqualTo(1);
        assertThat(runtime(ownerContext, () -> jdbc.update(
                        """
                        UPDATE buffer_rule
                        SET explicit_buffer_minutes = explicit_buffer_minutes
                        WHERE id = ?
                        """,
                        fixture.bufferRuleId())))
                .isEqualTo(1);

        assertIsolated(context(peer, workspace), fixture);
        assertIsolated(context(outsider, otherWorkspace), fixture);
        assertIsolated(WorkspaceContext.system(), fixture);
    }

    private Fixture createFixture() {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "私人知識行程",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = knowledge.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "私人轉乘",
                "預留四十五分鐘");
        CalendarKnowledgeBindingView binding = bindings.bindFact(
                "materialization-rls-binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        KnowledgeMaterializationProposalView proposal = materializations.prepare(
                "materialization-rls-proposal-" + planId,
                new KnowledgeMaterializationSource(
                        binding.sourceKind(),
                        binding.id(),
                        binding.sourceUpdatedAt(),
                        binding.revision(),
                        "TEST",
                        "materialization-rls-scope"),
                new KnowledgeMaterializationCommand.CreateTask(
                        "確認轉乘", null, TaskPriority.NORMAL));
        Place place =
                places.createPlace("私人車站", "測試地址", 25.0478, 121.5319, "STATION");
        var bufferRule = bufferRules.setExplicitBuffer(place.getId(), 45, 0);
        return new Fixture(proposal.id(), bufferRule.getId());
    }

    private void assertIsolated(WorkspaceContext context, Fixture fixture) {
        assertThat(runtime(context, this::counts)).containsExactly(0L, 0L);
        assertThat(runtime(context, () -> jdbc.update(
                        """
                        UPDATE knowledge_materialization
                        SET updated_at = updated_at
                        WHERE id = ?
                        """,
                        fixture.proposalId())))
                .isZero();
        assertThat(runtime(context, () -> jdbc.update(
                        """
                        UPDATE buffer_rule
                        SET explicit_buffer_minutes = explicit_buffer_minutes
                        WHERE id = ?
                        """,
                        fixture.bufferRuleId())))
                .isZero();
    }

    private List<Long> counts() {
        return List.of(
                jdbc.queryForObject(
                        "SELECT count(*) FROM knowledge_materialization",
                        Long.class),
                jdbc.queryForObject("SELECT count(*) FROM buffer_rule", Long.class));
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedPeer(
            UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private record Fixture(UUID proposalId, long bufferRuleId) {}
}
