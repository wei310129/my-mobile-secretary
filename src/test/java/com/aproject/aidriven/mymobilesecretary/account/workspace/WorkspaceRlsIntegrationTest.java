package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkspaceRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_rls_test_runtime";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createNonBypassRuntimeRole() {
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mms_rls_test_runtime') THEN
                        CREATE ROLE mms_rls_test_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + RUNTIME_ROLE);
        jdbcTemplate.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "
                + RUNTIME_ROLE);
    }

    @Test
    void transactionScopeDrivesDatabaseEnforcedWorkspaceAndActorIsolation() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seedAccount(firstActor, firstWorkspace, "First");
        seedAccount(secondActor, secondWorkspace, "Second");
        UUID householdPeer = UUID.randomUUID();
        seedUser(householdPeer, "Peer");

        long firstItem = insertItem(firstWorkspace, firstActor, "First item");
        long secondItem = insertItem(secondWorkspace, secondActor, "Second item");
        insertLineMessage(firstWorkspace, firstActor, "first private message");
        insertLineMessage(firstWorkspace, householdPeer, "peer private message");
        insertStoredMedia(firstWorkspace, firstActor, "first private media");
        insertStoredMedia(firstWorkspace, householdPeer, "peer private media");
        insertExternalContact(firstWorkspace, firstActor, "first private contact");
        insertExternalContact(firstWorkspace, householdPeer, "peer private contact");
        long firstBankDraft = insertBankTransferDraft(
                firstWorkspace, firstActor, "first masked recipient");
        long peerBankDraft = insertBankTransferDraft(
                firstWorkspace, householdPeer, "peer masked recipient");
        insertDraftRetention(firstWorkspace, firstActor, firstBankDraft, "first draft timing", 7);
        insertDraftRetention(firstWorkspace, householdPeer, peerBankDraft, "peer draft timing", 14);
        insertBloodDonation(firstWorkspace, firstActor, "2026-07-01");
        insertBloodDonation(firstWorkspace, householdPeer, "2026-07-02");
        long firstProduct = insertProductObservation(
                firstWorkspace, firstActor, "first private paint");
        long peerProduct = insertProductObservation(
                firstWorkspace, householdPeer, "peer private paint");
        insertObjectAnnotation(firstWorkspace, firstActor, firstProduct, "first private annotation");
        insertObjectAnnotation(firstWorkspace, householdPeer, peerProduct, "peer private annotation");
        insertPaymentNotice(firstWorkspace, firstActor, "first private bill");
        insertPaymentNotice(firstWorkspace, householdPeer, "peer private bill");
        insertTimeDisplayPreference(firstWorkspace, firstActor, "TWELVE_HOUR");
        insertTimeDisplayPreference(firstWorkspace, householdPeer, "TWENTY_FOUR_HOUR");
        insertConditionalVenueDraft(firstWorkspace, firstActor, "first conditional venue");
        insertConditionalVenueDraft(firstWorkspace, householdPeer, "peer conditional venue");
        insertUtilityBill(firstWorkspace, firstActor, "first home");
        insertUtilityBill(firstWorkspace, householdPeer, "peer home");
        insertVenueVisitInformation(firstWorkspace, firstActor, "first private venue");
        insertVenueVisitInformation(firstWorkspace, householdPeer, "peer private venue");

        WorkspaceContext first = new WorkspaceContext(
                firstActor, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext second = new WorkspaceContext(
                secondActor, secondWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext peer = new WorkspaceContext(
                householdPeer, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext integration = new WorkspaceContext(
                firstActor, firstWorkspace, WorkspaceChannel.INTEGRATION);

        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList("SELECT id FROM item ORDER BY id", Long.class)))
                .containsExactly(firstItem);
        assertThat(inRuntimeTransaction(second,
                () -> jdbcTemplate.queryForList("SELECT id FROM item ORDER BY id", Long.class)))
                .containsExactly(secondItem);
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("first private message");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("peer private message");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_name FROM stored_media ORDER BY id", String.class)))
                .containsExactly("first private media");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_name FROM stored_media ORDER BY id", String.class)))
                .containsExactly("peer private media");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_name FROM external_contact ORDER BY id", String.class)))
                .containsExactly("first private contact");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_name FROM external_contact ORDER BY id", String.class)))
                .containsExactly("peer private contact");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT displayed_recipient FROM bank_transfer_draft ORDER BY id",
                        String.class)))
                .containsExactly("first masked recipient");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT displayed_recipient FROM bank_transfer_draft ORDER BY id",
                        String.class)))
                .containsExactly("peer masked recipient");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT donation_date::text FROM blood_donation_record ORDER BY id",
                        String.class)))
                .containsExactly("2026-07-01");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT donation_date::text FROM blood_donation_record ORDER BY id",
                        String.class)))
                .containsExactly("2026-07-02");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT product_name FROM product_observation_draft ORDER BY id",
                        String.class)))
                .containsExactly("first private paint");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT product_name FROM product_observation_draft ORDER BY id",
                        String.class)))
                .containsExactly("peer private paint");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT detail FROM object_annotation ORDER BY id", String.class)))
                .containsExactly("first private annotation");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT detail FROM object_annotation ORDER BY id", String.class)))
                .containsExactly("peer private annotation");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM payment_notice ORDER BY id", String.class)))
                .containsExactly("first private bill");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM payment_notice ORDER BY id", String.class)))
                .containsExactly("peer private bill");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT source_kind FROM flexible_day_task_plan ORDER BY id",
                        String.class)))
                .containsExactly("PAYMENT_NOTICE");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT source_kind FROM flexible_day_task_plan ORDER BY id",
                        String.class)))
                .containsExactly("PAYMENT_NOTICE");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM draft_retention_binding ORDER BY id", String.class)))
                .containsExactly("first draft timing");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM draft_retention_binding ORDER BY id", String.class)))
                .containsExactly("peer draft timing");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT default_retention_days FROM draft_retention_preference",
                        Integer.class)))
                .containsExactly(7);
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT default_retention_days FROM draft_retention_preference",
                        Integer.class)))
                .containsExactly(14);
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_format FROM time_display_preference", String.class)))
                .containsExactly("TWELVE_HOUR");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT display_format FROM time_display_preference", String.class)))
                .containsExactly("TWENTY_FOUR_HOUR");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM conditional_venue_draft", String.class)))
                .containsExactly("first conditional venue");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM conditional_venue_draft", String.class)))
                .containsExactly("peer conditional venue");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT location_label FROM utility_bill_record", String.class)))
                .containsExactly("first home");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT location_label FROM utility_bill_record", String.class)))
                .containsExactly("peer home");
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT venue_name FROM venue_visit_information", String.class)))
                .containsExactly("first private venue");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT venue_name FROM venue_visit_information", String.class)))
                .containsExactly("peer private venue");
        assertThat(inRuntimeTransaction(integration,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("first private message");

        assertThat(inRuntimeTransaction(WorkspaceContext.authentication(),
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM item", Long.class)))
                .isZero();
        assertThat(inRuntimeTransaction(WorkspaceContext.system(),
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM item", Long.class)))
                .isZero();

        assertThatThrownBy(() -> inRuntimeTransaction(first, () -> {
            jdbcTemplate.update("""
                    INSERT INTO item (
                        name, created_at, inventory_quantity, shopping_needed, updated_at,
                        workspace_id, created_by_user_id)
                    VALUES ('cross-tenant write', CURRENT_TIMESTAMP, 0, FALSE,
                            CURRENT_TIMESTAMP, ?, ?)
                    """, secondWorkspace, firstActor);
            return null;
        })).isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(failure.getCause())
                        .hasMessageContaining("row-level security"));
    }

    private <T> T inRuntimeTransaction(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.scope', true)", String.class))
                        .isEqualTo(context.channel().name());
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.workspace_id', true)", String.class))
                        .isEqualTo(context.workspaceId().toString());
                jdbcTemplate.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private void seedAccount(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label + " user");
        jdbcTemplate.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'HOUSEHOLD', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, label + " workspace", actorId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
    }

    private long insertItem(UUID workspaceId, UUID actorId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO item (
                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, CURRENT_TIMESTAMP, 0, FALSE, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, name, workspaceId, actorId);
    }

    private void insertLineMessage(UUID workspaceId, UUID actorId, String content) {
        jdbcTemplate.update("""
                INSERT INTO line_message_log (
                    direction, message_type, content, created_at, pinned, expires_at,
                    workspace_id, created_by_user_id)
                VALUES ('IN', 'TEXT', ?, CURRENT_TIMESTAMP, FALSE,
                        CURRENT_TIMESTAMP + INTERVAL '1 day', ?, ?)
                """, content, workspaceId, actorId);
    }

    private void insertStoredMedia(UUID workspaceId, UUID actorId, String displayName) {
        UUID objectId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO stored_media (
                    source_type, media_kind, display_name, media_type, size_bytes, sha256,
                    storage_key, status, created_at, workspace_id, created_by_user_id)
                VALUES ('APP', 'IMAGE', ?, 'image/png', 8, ?, ?, 'AVAILABLE',
                        CURRENT_TIMESTAMP, ?, ?)
                """, displayName, "0".repeat(64),
                objectId.toString().substring(0, 2) + "/" + objectId, workspaceId, actorId);
    }

    private void insertExternalContact(UUID workspaceId, UUID actorId, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO external_contact (
                    canonical_key, display_name, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, displayName, displayName, workspaceId, actorId);
    }

    private long insertBankTransferDraft(UUID workspaceId, UUID actorId, String recipient) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO bank_transfer_draft (
                    displayed_recipient, purpose, amount_twd, transferred_at, status,
                    expires_at, created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, '訂金', 3000, CURRENT_DATE, 'PENDING_RECIPIENT',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, recipient, workspaceId, actorId);
    }

    private void insertDraftRetention(UUID workspaceId, UUID actorId, long draftId,
                                      String title, int defaultDays) {
        jdbcTemplate.update("""
                INSERT INTO draft_retention_preference (
                    default_retention_days, default_reminder_days_before,
                    default_reminder_time, settings_confirmed, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 0, TIME '20:00', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, defaultDays, workspaceId, actorId);
        jdbcTemplate.update("""
                INSERT INTO draft_retention_binding (
                    draft_type, draft_id, title, last_edited_at,
                    uses_default_retention, uses_default_reminder,
                    expires_at, remind_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES ('BANK_TRANSFER', ?, ?, CURRENT_TIMESTAMP,
                        TRUE, TRUE, CURRENT_TIMESTAMP + INTERVAL '2 days',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?)
                """, draftId, title, workspaceId, actorId);
    }

    private void insertBloodDonation(UUID workspaceId, UUID actorId, String donationDate) {
        jdbcTemplate.update("""
                INSERT INTO blood_donation_record (
                    donation_date, source_type, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (CAST(? AS DATE), 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, donationDate, workspaceId, actorId);
    }

    private long insertProductObservation(UUID workspaceId, UUID actorId, String productName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO product_observation_draft (
                    product_name, status, expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'PENDING_PURPOSE', CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, productName, workspaceId, actorId);
    }

    private void insertObjectAnnotation(UUID workspaceId, UUID actorId,
                                        long targetId, String detail) {
        jdbcTemplate.update("""
                INSERT INTO object_annotation (
                    target_type, target_id, subject, detail, created_at,
                    workspace_id, created_by_user_id)
                VALUES ('PRODUCT_OBSERVATION', ?, 'private product', ?,
                        CURRENT_TIMESTAMP, ?, ?)
                """, targetId, detail, workspaceId, actorId);
    }

    private void insertPaymentNotice(UUID workspaceId, UUID actorId, String title) {
        long taskId = jdbcTemplate.queryForObject("""
                INSERT INTO task (
                    title, status, priority, due_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'SCHEDULED', 'NORMAL', CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, "reminder for " + title, workspaceId, actorId);
        long planId = jdbcTemplate.queryForObject("""
                INSERT INTO flexible_day_task_plan (
                    task_id, target_date, remind_at, source_kind, status,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, CURRENT_DATE + 3, CURRENT_TIMESTAMP + INTERVAL '1 day',
                        'PAYMENT_NOTICE', 'SCHEDULED', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, taskId, workspaceId, actorId);
        jdbcTemplate.update("""
                INSERT INTO payment_notice (
                    title, due_date, reminder_lead_days, flexible_plan_id, status,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, CURRENT_DATE + 3, 2, ?, 'SCHEDULED', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?)
                """, title, planId, workspaceId, actorId);
    }

    private void insertTimeDisplayPreference(
            UUID workspaceId, UUID actorId, String displayFormat) {
        jdbcTemplate.update("""
                INSERT INTO time_display_preference (
                    display_format, updated_at, workspace_id, created_by_user_id)
                VALUES (?, CURRENT_TIMESTAMP, ?, ?)
                """, displayFormat, workspaceId, actorId);
    }

    private void insertConditionalVenueDraft(UUID workspaceId, UUID actorId, String title) {
        long taskId = jdbcTemplate.queryForObject("""
                INSERT INTO task (
                    title, status, priority, due_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'SCHEDULED', 'NORMAL', CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, "decision for " + title, workspaceId, actorId);
        jdbcTemplate.update("""
                INSERT INTO conditional_venue_draft (
                    title, event_start_at, event_end_at, primary_place_name,
                    fallback_place_name, decision_at, decision_task_id, status,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, CURRENT_TIMESTAMP + INTERVAL '2 days',
                        CURRENT_TIMESTAMP + INTERVAL '2 days 1 hour', 'gym', 'home',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', ?, 'PENDING',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, title, taskId, workspaceId, actorId);
    }

    private void insertUtilityBill(UUID workspaceId, UUID actorId, String location) {
        jdbcTemplate.update("""
                INSERT INTO utility_bill_record (
                    import_batch_id, provider, location_label, billing_month,
                    usage_kwh, amount_twd, status, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, '台灣電力公司', ?, DATE '2024-03-01', 640, 1248,
                        'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), location, workspaceId, actorId);
    }

    private void insertVenueVisitInformation(UUID workspaceId, UUID actorId, String venue) {
        jdbcTemplate.update("""
                INSERT INTO venue_visit_information (
                    venue_name, normalized_venue, subject, details, reservation_required,
                    minimum_group_size, source_type, status, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, 'private exhibit', 'private visit rule', TRUE, 10,
                        'TEXT', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, venue, venue.replace(" ", ""), workspaceId, actorId);
    }
}
