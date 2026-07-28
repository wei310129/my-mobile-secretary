package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import java.sql.Timestamp;
import java.time.Duration;
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

class CalendarSharedAdoptionRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_shared_adoption_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarActivityRepository activities;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private CalendarShareService shares;
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
                        WHERE rolname = 'mms_calendar_shared_adoption_rls_runtime') THEN
                        CREATE ROLE mms_calendar_shared_adoption_rls_runtime
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
    void wholePlanViewerCanReadOwnerCoreAndKeepPersonalAdoptionPrivate() {
        assertSchemaContract();
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seedHousehold(owner, recipient, peer, admin, workspace);
        seedPersonal(outsider, otherWorkspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        CoreIds core = inContext(ownerContext, () -> createSharedCore(recipient));
        WorkspaceContext recipientContext = context(recipient, workspace);

        assertThat(runtime(recipientContext, () -> coreCount("calendar_plan", core.planId())))
                .isEqualTo(1L);
        assertThat(runtime(
                        recipientContext,
                        () -> coreCount("calendar_activity", core.activityId())))
                .isEqualTo(1L);
        assertThat(runtime(
                        recipientContext,
                        () -> coreCount("calendar_time_node", core.nodeId())))
                .isEqualTo(1L);
        assertThat(runtime(recipientContext, () -> updateCore("calendar_plan", core.planId())))
                .isZero();
        assertThat(runtime(
                        recipientContext,
                        () -> updateCore("calendar_activity", core.activityId())))
                .isZero();
        assertThat(runtime(
                        recipientContext,
                        () -> updateCore("calendar_time_node", core.nodeId())))
                .isZero();

        UUID adoptionId = UUID.randomUUID();
        assertThat(runtime(
                        recipientContext,
                        () -> insertAdoption(
                                adoptionId,
                                core.planId(),
                                workspace,
                                recipient,
                                owner)))
                .isEqualTo(1);
        assertThat(runtime(
                        recipientContext,
                        () -> insertAdoptionNode(
                                adoptionId,
                                core,
                                workspace,
                                recipient,
                                owner)))
                .isEqualTo(1);
        assertThat(runtime(
                        recipientContext,
                        () -> adoptionCount(adoptionId)))
                .isEqualTo(1L);
        assertThat(runtime(
                        recipientContext,
                        () -> adoptionNodeCount(adoptionId, core.nodeId())))
                .isEqualTo(1L);

        assertAdoptionIsPrivate(ownerContext, adoptionId, core.nodeId());
        assertAdoptionIsPrivate(
                context(peer, workspace), adoptionId, core.nodeId());
        assertAdoptionIsPrivate(
                context(admin, workspace), adoptionId, core.nodeId());
        assertAdoptionIsPrivate(
                context(outsider, otherWorkspace), adoptionId, core.nodeId());
        assertAdoptionIsPrivate(
                WorkspaceContext.system(), adoptionId, core.nodeId());

        assertPrivateDescendants(context(peer, workspace), core);
        assertPrivateDescendants(context(admin, workspace), core);
    }

    private void assertSchemaContract() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'calendar_adoption',
                              'calendar_adoption_node')
                          AND column_name = 'source_created_by_user_id'
                        """,
                        Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_class
                        WHERE oid IN (
                            'calendar_plan'::regclass,
                            'calendar_activity'::regclass,
                            'calendar_time_node'::regclass,
                            'calendar_adoption'::regclass,
                            'calendar_adoption_node'::regclass)
                          AND relrowsecurity
                          AND relforcerowsecurity
                        """,
                        Long.class))
                .isEqualTo(5L);
    }

    private CoreIds createSharedCore(UUID recipient) {
        UUID planId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "分享行程",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        activities.saveAndFlush(CalendarActivityEntity.create(
                activityId,
                planId,
                "分享活動",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                nodeId,
                planId,
                activityId,
                CalendarTimeNode.relativeToOwner(
                        "leave",
                        "出發",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ofMinutes(-20),
                        Criticality.NORMAL,
                        Adjustability.FLEXIBLE),
                NOW));
        shares.createViewerShare("shared-adoption-rls", planId, recipient, 1);
        return new CoreIds(planId, activityId, nodeId);
    }

    private int insertAdoption(
            UUID adoptionId,
            UUID planId,
            UUID workspace,
            UUID recipient,
            UUID sourceOwner) {
        return jdbc.update(
                """
                INSERT INTO calendar_adoption (
                    id, plan_id, status, revision, version, created_at, updated_at,
                    workspace_id, created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, 'ACTIVE', 1, 0, ?, ?, ?, ?, ?)
                """,
                adoptionId,
                planId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                workspace,
                recipient,
                sourceOwner);
    }

    private int insertAdoptionNode(
            UUID adoptionId,
            CoreIds core,
            UUID workspace,
            UUID recipient,
            UUID sourceOwner) {
        return jdbc.update(
                """
                INSERT INTO calendar_adoption_node (
                    adoption_id, plan_id, node_id, node_revision, created_at,
                    workspace_id, created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """,
                adoptionId,
                core.planId(),
                core.nodeId(),
                Timestamp.from(NOW),
                workspace,
                recipient,
                sourceOwner);
    }

    private void assertAdoptionIsPrivate(
            WorkspaceContext context, UUID adoptionId, UUID nodeId) {
        assertThat(runtime(context, () -> adoptionCount(adoptionId))).isZero();
        assertThat(runtime(
                        context,
                        () -> adoptionNodeCount(adoptionId, nodeId)))
                .isZero();
    }

    private void assertPrivateDescendants(WorkspaceContext context, CoreIds core) {
        assertThat(runtime(
                        context,
                        () -> coreCount("calendar_activity", core.activityId())))
                .isZero();
        assertThat(runtime(
                        context,
                        () -> coreCount("calendar_time_node", core.nodeId())))
                .isZero();
    }

    private long coreCount(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?",
                Long.class,
                id);
    }

    private int updateCore(String table, UUID id) {
        return jdbc.update(
                "UPDATE " + table + " SET updated_at = updated_at WHERE id = ?",
                id);
    }

    private long adoptionCount(UUID adoptionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_adoption WHERE id = ?",
                Long.class,
                adoptionId);
    }

    private long adoptionNodeCount(UUID adoptionId, UUID nodeId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_adoption_node
                WHERE adoption_id = ? AND node_id = ?
                """,
                Long.class,
                adoptionId,
                nodeId);
    }

    private void seedHousehold(
            UUID owner,
            UUID recipient,
            UUID peer,
            UUID admin,
            UUID workspace) {
        seedUser(owner, "shared adoption owner");
        seedUser(recipient, "shared adoption recipient");
        seedUser(peer, "shared adoption peer");
        seedUser(admin, "shared adoption admin");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'shared adoption household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        addMember(workspace, peer, owner, "MEMBER");
        addMember(workspace, admin, owner, "ADMIN");
    }

    private void seedPersonal(UUID actor, UUID workspace) {
        seedUser(actor, "shared adoption outsider");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'shared adoption personal', 'PERSONAL', ?,
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

    private record CoreIds(UUID planId, UUID activityId, UUID nodeId) {}
}
