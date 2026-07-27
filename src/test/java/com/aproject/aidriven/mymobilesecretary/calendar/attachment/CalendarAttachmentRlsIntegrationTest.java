package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
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

class CalendarAttachmentRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_calendar_attachment_rls_runtime";
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    @Autowired private CalendarAttachmentBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private MediaStorageService media;
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
                        WHERE rolname = 'mms_calendar_attachment_rls_runtime') THEN
                        CREATE ROLE mms_calendar_attachment_rls_runtime
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
    void runtimeRoleAndApplicationFiltersHideOwnerMediaBindingAndReplacementLedger() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_class
                        WHERE oid IN (
                            'stored_media'::regclass,
                            'calendar_attachment_binding'::regclass,
                            'calendar_attachment_replacement'::regclass)
                          AND relrowsecurity AND relforcerowsecurity
                        """,
                        Long.class))
                .isEqualTo(3L);
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seed(owner, peer, workspace, outsider, otherWorkspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> {
            UUID planId = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    planId,
                    "RLS 船票",
                    CalendarPlacement.point(
                            Instant.parse("2027-08-01T00:00:00Z"),
                            ZoneId.of("Asia/Taipei")),
                    Instant.parse("2026-07-25T00:00:00Z")));
            StoredMedia stored = media.store(
                    StoredMedia.SourceType.APP,
                    "rls-secret-source",
                    "RLS secret",
                    "secret-ticket.png",
                    "application/octet-stream",
                    PNG);
            CalendarAttachmentBindingView binding = bindings.bind(
                    "rls-bind",
                    stored.getId(),
                    CalendarAttachmentTarget.plan(planId),
                    "RLS 船票",
                    0);
            StoredMedia replacement = media.store(
                    StoredMedia.SourceType.APP,
                    "rls-replacement-source",
                    "RLS replacement",
                    "replacement-ticket.png",
                    "application/octet-stream",
                    PNG);
            bindings.replace(
                    "rls-replace",
                    binding.id(),
                    replacement.getId(),
                    binding.revision());
            return new Fixture(planId, stored.getId(), binding.id());
        });

        assertThat(runtime(ownerContext, () -> mediaCount(fixture.mediaId())))
                .isEqualTo(1L);
        assertThat(runtime(ownerContext, () -> bindingCount(fixture.bindingId())))
                .isEqualTo(1L);
        assertThat(runtime(ownerContext, () -> replacementCount(fixture.bindingId())))
                .isEqualTo(1L);
        assertIsolated(context(peer, workspace), fixture);
        assertIsolated(context(outsider, otherWorkspace), fixture);
        assertIsolated(WorkspaceContext.system(), fixture);
        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> bindings.bind(
                                "peer-forged-bind",
                                fixture.mediaId(),
                                CalendarAttachmentTarget.plan(fixture.planId()),
                                "偷看船票",
                                0)))
                .isInstanceOf(NotFoundException.class);
    }

    private void assertIsolated(WorkspaceContext context, Fixture fixture) {
        assertThat(runtime(context, () -> mediaCount(fixture.mediaId())))
                .isZero();
        assertThat(runtime(context, () -> bindingCount(fixture.bindingId())))
                .isZero();
        assertThat(runtime(context, () -> replacementCount(fixture.bindingId())))
                .isZero();
        assertThat(runtime(context, () -> jdbc.update(
                        "UPDATE calendar_attachment_binding "
                                + "SET display_name = 'leaked' WHERE id = ?",
                        fixture.bindingId())))
                .isZero();
    }

    private long mediaCount(long mediaId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stored_media WHERE id = ?",
                Long.class,
                mediaId);
    }

    private long bindingCount(UUID bindingId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_attachment_binding WHERE id = ?",
                Long.class,
                bindingId);
    }

    private long replacementCount(UUID oldBindingId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_attachment_replacement
                WHERE old_binding_id = ?
                """,
                Long.class,
                oldBindingId);
    }

    private void seed(
            UUID owner,
            UUID peer,
            UUID workspace,
            UUID outsider,
            UUID otherWorkspace) {
        seedUser(owner, "owner");
        seedUser(peer, "peer");
        seedUser(outsider, "outsider");
        seedWorkspace(workspace, owner, "HOUSEHOLD");
        seedWorkspace(otherWorkspace, outsider, "PERSONAL");
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, peer, owner, "MEMBER");
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

    private void seedWorkspace(
            UUID id, UUID creator, String type) {
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id,
                    created_at, updated_at)
                VALUES (?, 'attachment rls', ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                type,
                creator);
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

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (var ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (var ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private record Fixture(UUID planId, long mediaId, UUID bindingId) {}
}
