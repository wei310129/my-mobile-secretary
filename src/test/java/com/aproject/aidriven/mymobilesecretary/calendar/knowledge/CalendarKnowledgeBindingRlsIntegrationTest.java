package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarKnowledgeBindingRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_calendar_knowledge_binding_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-25T04:00:00Z");

    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private UserKnowledgeService facts;
    @Autowired private ObjectAnnotationRepository annotations;
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
                            'mms_calendar_knowledge_binding_rls_runtime') THEN
                        CREATE ROLE mms_calendar_knowledge_binding_rls_runtime
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
    void noBypassRuntimeRoleIsolatesFactAndAnnotationBindingsByActor() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seed(owner, workspace, "knowledge binding owner");
        seedPeer(peer, owner, workspace, "same workspace peer");
        seed(outsider, otherWorkspace, "other workspace actor");
        WorkspaceContext ownerContext = context(owner, workspace);

        Fixture fixture = inContext(ownerContext, this::createFixture);

        assertThat(jdbc.queryForObject(
                        "SELECT rolbypassrls FROM pg_roles WHERE rolname = ?",
                        Boolean.class,
                        RUNTIME_ROLE))
                .isFalse();
        assertThat(runtime(ownerContext, this::counts)).containsExactly(1L, 1L);
        assertThat(runtime(ownerContext, () -> updateFact(fixture.factBindingId())))
                .isEqualTo(1);
        assertThat(runtime(
                        ownerContext,
                        () -> updateAnnotation(fixture.annotationBindingId())))
                .isEqualTo(1);

        assertIsolated(context(peer, workspace), fixture);
        assertIsolated(context(outsider, otherWorkspace), fixture);
        assertIsolated(WorkspaceContext.system(), fixture);
    }

    private Fixture createFixture() {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "私人知識綁定",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "私人轉乘",
                "不要分享");
        ObjectAnnotation annotation = annotations.saveAndFlush(
                ObjectAnnotation.create(
                        ObjectAnnotation.TargetType.MEDIA,
                        701L,
                        "私人附件註記",
                        "只限本人",
                        NOW));
        CalendarKnowledgeBindingView factBinding = bindings.bindFact(
                "rls-fact-binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        CalendarKnowledgeBindingView annotationBinding = bindings.bindAnnotation(
                "rls-annotation-binding-" + planId,
                annotation.getId(),
                CalendarKnowledgeTarget.plan(planId));
        return new Fixture(factBinding.id(), annotationBinding.id());
    }

    private void assertIsolated(WorkspaceContext context, Fixture fixture) {
        assertThat(runtime(context, this::counts)).containsExactly(0L, 0L);
        assertThat(runtime(context, () -> updateFact(fixture.factBindingId())))
                .isZero();
        assertThat(runtime(
                        context,
                        () -> updateAnnotation(fixture.annotationBindingId())))
                .isZero();
    }

    private List<Long> counts() {
        return List.of(
                jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_knowledge_fact_binding",
                        Long.class),
                jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_knowledge_annotation_binding",
                        Long.class));
    }

    private int updateFact(UUID bindingId) {
        return jdbc.update(
                """
                UPDATE calendar_knowledge_fact_binding
                SET updated_at = updated_at
                WHERE id = ?
                """,
                bindingId);
    }

    private int updateAnnotation(UUID bindingId) {
        return jdbc.update(
                """
                UPDATE calendar_knowledge_annotation_binding
                SET updated_at = updated_at
                WHERE id = ?
                """,
                bindingId);
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

    private record Fixture(
            UUID factBindingId, UUID annotationBindingId) {}
}
