package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeExcerptService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarShareRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_share_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-25T07:00:00Z");

    @Autowired private CalendarShareService shares;
    @Autowired private CalendarShareContentGrantService grants;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarKnowledgeExcerptService excerpts;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private UserKnowledgeService facts;
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
                        WHERE rolname = 'mms_calendar_share_rls_runtime') THEN
                        CREATE ROLE mms_calendar_share_rls_runtime
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
    void ownerAndRecipientPoliciesAreDistinctAndFailClosed() {
        assertSchemaContract();
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seedHousehold(owner, recipient, peer, workspace);
        seedPersonal(outsider, otherWorkspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        IDs ids = inContext(ownerContext, () -> createSharedExcerpt(recipient));

        assertThat(runtime(ownerContext, this::shareCount)).isEqualTo(1L);
        assertThat(runtime(ownerContext, this::grantCount)).isEqualTo(1L);
        assertThat(runtime(ownerContext, this::planCount)).isEqualTo(1L);
        assertThat(runtime(ownerContext, () -> updateShare(ids.shareId()))).isEqualTo(1);
        assertThat(runtime(ownerContext, () -> updatePlan(ids.planId()))).isEqualTo(1);
        WorkspaceContext recipientContext = context(recipient, workspace);
        assertThat(runtime(recipientContext, this::shareCount)).isEqualTo(1L);
        assertThat(runtime(recipientContext, this::grantCount)).isEqualTo(1L);
        assertThat(runtime(recipientContext, this::excerptCount)).isEqualTo(1L);
        assertThat(runtime(recipientContext, this::planCount)).isEqualTo(1L);
        assertThat(runtime(
                        recipientContext,
                        () -> grants.readKnowledgeExcerpt(ids.grantId()).title()))
                .isEqualTo("可分享摘要");
        assertThat(runtime(recipientContext, () -> updateShare(ids.shareId()))).isZero();
        assertThat(runtime(recipientContext, () -> updateGrant(ids.grantId()))).isZero();
        assertThat(runtime(recipientContext, () -> updatePlan(ids.planId()))).isZero();
        assertIsolated(context(peer, workspace), ids);
        assertIsolated(context(outsider, otherWorkspace), ids);
        assertIsolated(WorkspaceContext.system(), ids);
    }

    private void assertSchemaContract() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_class
                        WHERE oid IN (
                            'calendar_share'::regclass,
                            'calendar_share_content_grant'::regclass,
                            'calendar_share_outbox'::regclass)
                          AND relrowsecurity AND relforcerowsecurity
                        """,
                        Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_share_plan',
                            'fk_calendar_content_grant_share',
                            'fk_calendar_content_grant_attachment',
                            'fk_calendar_content_grant_excerpt')
                        """,
                        Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname IN (
                            'trg_calendar_share_content_lifecycle',
                            'trg_calendar_attachment_content_lifecycle',
                            'trg_calendar_excerpt_content_lifecycle',
                            'trg_stored_media_calendar_content_lifecycle',
                            'trg_calendar_plan_share_lifecycle')
                          AND NOT tgisinternal
                        """,
                        Long.class))
                .isEqualTo(5L);
    }

    private IDs createSharedExcerpt(UUID recipient) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "RLS 分享",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "公開摘要來源",
                "私人原文");
        var binding = bindings.bindFact(
                "rls-bind-fact",
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        var draft = excerpts.createFactDraft(
                "rls-excerpt", binding.id(), "可分享摘要", "只允許指定收件者");
        var excerpt = excerpts.approve(draft.id(), 1);
        var share =
                shares.createViewerShare("rls-share", planId, recipient, 1);
        var grant = grants.grantKnowledgeExcerpt(
                "rls-grant", share.id(), excerpt.id());
        return new IDs(planId, share.id(), grant.id());
    }

    private void assertIsolated(WorkspaceContext context, IDs ids) {
        assertThat(runtime(context, this::shareCount)).isZero();
        assertThat(runtime(context, this::grantCount)).isZero();
        assertThat(runtime(context, this::excerptCount)).isZero();
        assertThat(runtime(context, this::planCount)).isZero();
        assertThat(runtime(context, () -> updateShare(ids.shareId()))).isZero();
        assertThat(runtime(context, () -> updateGrant(ids.grantId()))).isZero();
        assertThat(runtime(context, () -> updatePlan(ids.planId()))).isZero();
    }

    private long shareCount() {
        return jdbc.queryForObject("SELECT count(*) FROM calendar_share", Long.class);
    }

    private long grantCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_share_content_grant", Long.class);
    }

    private long excerptCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_knowledge_excerpt", Long.class);
    }

    private long planCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_plan", Long.class);
    }

    private int updateShare(UUID id) {
        return jdbc.update(
                "UPDATE calendar_share SET updated_at = updated_at WHERE id = ?", id);
    }

    private int updateGrant(UUID id) {
        return jdbc.update(
                """
                UPDATE calendar_share_content_grant
                SET updated_at = updated_at WHERE id = ?
                """,
                id);
    }

    private int updatePlan(UUID id) {
        return jdbc.update(
                "UPDATE calendar_plan SET updated_at = updated_at WHERE id = ?",
                id);
    }

    private void seedHousehold(
            UUID owner, UUID recipient, UUID peer, UUID workspace) {
        seedUser(owner, "RLS owner");
        seedUser(recipient, "RLS recipient");
        seedUser(peer, "RLS peer");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'RLS household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        addMember(workspace, peer, owner, "MEMBER");
    }

    private void seedPersonal(UUID actor, UUID workspace) {
        seedUser(actor, "RLS outsider");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'RLS personal', 'PERSONAL', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
    }

    private void seedUser(UUID id, String name) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                name);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
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

    private record IDs(UUID planId, UUID shareId, UUID grantId) {}
}
