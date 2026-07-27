package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarAuthoritativeCapabilityInvariantIntegrationTest
        extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_calendar_capability_invariant_runtime";
    private static final Instant NOW =
            Instant.parse("2026-07-26T09:00:00Z");

    @Autowired private CalendarShareService shares;
    @Autowired
    private CalendarAuthoritativeCapabilityService capabilities;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTimeNodeRepository nodes;
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
                            'mms_calendar_capability_invariant_runtime') THEN
                        CREATE ROLE mms_calendar_capability_invariant_runtime
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
    void directGrantAgainstViewerShareRollsBackEvenWithMatchingOutbox() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "viewer-target");
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "viewer-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        UUID capabilityId = UUID.randomUUID();

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> {
                            insertDirectCapability(
                                    fixture,
                                    share.id(),
                                    capabilityId,
                                    nodeId,
                                    "viewer-direct-grant");
                            insertCapabilityOutbox(
                                    fixture,
                                    share.id(),
                                    capabilityId,
                                    1,
                                    "AUTHORITATIVE_CAPABILITY_GRANTED",
                                    "viewer-direct-outbox");
                            return null;
                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(
                        "capability exceeds active editor scope");
        assertThat(capabilityCount(capabilityId)).isZero();
        assertThat(capabilityEventCount(capabilityId)).isZero();
    }

    @Test
    void directGrantOutsideSelectedNodeScopeRollsBackWithMatchingOutbox() {
        Fixture fixture = fixture();
        UUID selectedNodeId = node(fixture, "selected-target");
        UUID outsideNodeId = node(fixture, "outside-target");
        CalendarShareView share = selectedEditorShare(
                fixture, selectedNodeId, "selected-editor");
        UUID capabilityId = UUID.randomUUID();

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> {
                            insertDirectCapability(
                                    fixture,
                                    share.id(),
                                    capabilityId,
                                    outsideNodeId,
                                    "outside-direct-grant");
                            insertCapabilityOutbox(
                                    fixture,
                                    share.id(),
                                    capabilityId,
                                    1,
                                    "AUTHORITATIVE_CAPABILITY_GRANTED",
                                    "outside-direct-outbox");
                            return null;
                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(
                        "capability exceeds active editor scope");
        assertThat(capabilityCount(capabilityId)).isZero();
        assertThat(capabilityEventCount(capabilityId)).isZero();
    }

    @Test
    void legalDirectGrantWithoutGrantedOutboxRollsBackAtCommit() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "legal-target");
        CalendarShareView share =
                selectedEditorShare(fixture, nodeId, "legal-editor");
        UUID capabilityId = UUID.randomUUID();

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> {
                            insertDirectCapability(
                                    fixture,
                                    share.id(),
                                    capabilityId,
                                    nodeId,
                                    "missing-granted-outbox");
                            return null;
                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("requires durable outbox");
        assertThat(capabilityCount(capabilityId)).isZero();
    }

    @Test
    void directActiveToRevokedWithoutRevokedOutboxRollsBackAtCommit() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "revoke-target");
        CalendarShareView share =
                selectedEditorShare(fixture, nodeId, "revoke-editor");
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "official-grant-before-direct-revoke",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.NODE,
                                nodeId,
                                2)));

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> jdbc.update(
                                """
                                UPDATE
                                    calendar_authoritative_editor_capability
                                SET status = 'REVOKED',
                                    capability_revision = 2,
                                    revoke_request_hash = ?,
                                    revoked_at = ?
                                WHERE id = ?
                                """,
                                CalendarShareService.hash(
                                        "direct-revoke-without-outbox"),
                                Timestamp.from(NOW.plusSeconds(60)),
                                capability.id())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("requires durable outbox");
        assertThat(capabilityState(capability.id()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("capability_revision", 1L);
        assertThat(capabilityEventCount(
                        capability.id(),
                        "AUTHORITATIVE_CAPABILITY_REVOKED"))
                .isZero();
    }

    @Test
    void officialGrantAndRevokeReplayPreservePayloadAndExactlyOnceOutbox() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "official-target");
        CalendarShareView share =
                selectedEditorShare(fixture, nodeId, "official-editor");
        CalendarAuthoritativeCapabilityGrant grant =
                new CalendarAuthoritativeCapabilityGrant(
                        "official-capability-grant",
                        share.id(),
                        CalendarAuthoritativeCapabilityGrant.Scope.NODE,
                        nodeId,
                        2);

        CalendarAuthoritativeCapabilityView granted = inContext(
                fixture.ownerContext(), () -> capabilities.grant(grant));
        CalendarAuthoritativeCapabilityView grantReplay = inContext(
                fixture.ownerContext(), () -> capabilities.grant(grant));
        CalendarAuthoritativeCapabilityView revoked = inContext(
                fixture.ownerContext(),
                () -> capabilities.revoke(
                        "official-capability-revoke",
                        granted.id(),
                        1));
        CalendarAuthoritativeCapabilityView revokeReplay = inContext(
                fixture.ownerContext(),
                () -> capabilities.revoke(
                        "official-capability-revoke",
                        granted.id(),
                        1));

        assertThat(grantReplay).isEqualTo(granted);
        assertThat(revokeReplay).isEqualTo(revoked);
        assertThat(revoked.status())
                .isEqualTo(
                        CalendarAuthoritativeCapabilityView.Status.REVOKED);
        assertThat(revoked.revision()).isEqualTo(2);
        assertThat(capabilityEvents(granted.id()))
                .containsExactly(
                        Map.of(
                                "operation_request_hash",
                                CalendarShareService.hash(
                                        "official-capability-grant"),
                                "event_type",
                                "AUTHORITATIVE_CAPABILITY_GRANTED",
                                "payload_text",
                                "capabilityId="
                                        + granted.id()
                                        + ";capabilityRevision=1;scope=NODE"
                                        + ";nodeId="
                                        + nodeId),
                        Map.of(
                                "operation_request_hash",
                                CalendarShareService.hash(
                                        "official-capability-revoke"),
                                "event_type",
                                "AUTHORITATIVE_CAPABILITY_REVOKED",
                                "payload_text",
                                "capabilityId="
                                        + granted.id()
                                        + ";capabilityRevision=2"
                                        + ";cause=EXPLICIT_REVOKE"));
    }

    private CalendarShareView selectedEditorShare(
            Fixture fixture, UUID nodeId, String key) {
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        key + "-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        return inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        key + "-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
    }

    private void insertDirectCapability(
            Fixture fixture,
            UUID shareId,
            UUID capabilityId,
            UUID nodeId,
            String requestKey) {
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_editor_capability (
                    id, share_id, plan_id, node_id, capability_scope,
                    status, capability_revision, grant_request_hash,
                    revoke_request_hash, granted_at, revoked_at,
                    workspace_id, created_by_user_id, grantee_user_id)
                VALUES (?, ?, ?, ?, 'NODE', 'ACTIVE', 1, ?, NULL,
                        ?, NULL, ?, ?, ?)
                """,
                capabilityId,
                shareId,
                fixture.planId(),
                nodeId,
                CalendarShareService.hash(requestKey),
                Timestamp.from(NOW),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId(),
                fixture.recipient());
    }

    private void insertCapabilityOutbox(
            Fixture fixture,
            UUID shareId,
            UUID capabilityId,
            long revision,
            String eventType,
            String requestKey) {
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, authoritative_mutation_id,
                    life_recorded_at, grantee_user_id, payload_text,
                    status, created_at, workspace_id,
                    created_by_user_id)
                VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?,
                        ?, 'PENDING', ?, ?, ?)
                """,
                UUID.randomUUID(),
                CalendarShareService.hash(requestKey),
                eventType,
                shareId,
                fixture.recipient(),
                "capabilityId="
                        + capabilityId
                        + ";capabilityRevision="
                        + revision
                        + ";forged=true",
                Timestamp.from(NOW),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "capability invariant owner");
        seedUser(recipient, "capability invariant recipient");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id,
                    created_at, updated_at)
                VALUES (?, 'capability invariant household',
                        'HOUSEHOLD', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        WorkspaceContext ownerContext = context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> {
            UUID id = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    id,
                    "capability invariant plan",
                    CalendarPlacement.point(
                            NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            return id;
        });
        return new Fixture(
                ownerContext,
                context(recipient, workspace),
                recipient,
                planId);
    }

    private UUID node(Fixture fixture, String key) {
        return inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(key, key, NOW),
                                NOW))
                        .getId());
    }

    private long capabilityCount(UUID capabilityId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_authoritative_editor_capability
                WHERE id = ?
                """,
                Long.class,
                capabilityId);
    }

    private long capabilityEventCount(UUID capabilityId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_share_outbox
                WHERE payload_text LIKE ?
                """,
                Long.class,
                "capabilityId=" + capabilityId + ";%");
    }

    private long capabilityEventCount(
            UUID capabilityId, String eventType) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_share_outbox
                WHERE event_type = ? AND payload_text LIKE ?
                """,
                Long.class,
                eventType,
                "capabilityId=" + capabilityId + ";%");
    }

    private Map<String, Object> capabilityState(UUID capabilityId) {
        return jdbc.queryForMap(
                """
                SELECT status, capability_revision
                FROM calendar_authoritative_editor_capability
                WHERE id = ?
                """,
                capabilityId);
    }

    private List<Map<String, Object>> capabilityEvents(
            UUID capabilityId) {
        return jdbc.queryForList(
                """
                SELECT operation_request_hash, event_type, payload_text
                FROM calendar_share_outbox
                WHERE payload_text LIKE ?
                ORDER BY created_at, event_type
                """,
                "capabilityId=" + capabilityId + ";%");
    }

    private void seedUser(UUID id, String name) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                name);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role,
                    created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private static WorkspaceContext context(
            UUID actor, UUID workspace) {
        return new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(
            WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(
            WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions)
                    .execute(status -> {
                        jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                        return work.get();
                    });
        }
    }

    private record Fixture(
            WorkspaceContext ownerContext,
            WorkspaceContext recipientContext,
            UUID recipient,
            UUID planId) {}
}
