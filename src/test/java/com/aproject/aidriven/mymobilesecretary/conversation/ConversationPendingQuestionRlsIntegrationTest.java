package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationPendingQuestionRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_pending_question_rls_runtime";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void grantRuntimeRole() {
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles
                                   WHERE rolname = 'mms_pending_question_rls_runtime') THEN
                        CREATE ROLE mms_pending_question_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + RUNTIME_ROLE);
    }

    @Test
    void pendingQuestionIsInvisibleAcrossActorAndWorkspaceUnderNoBypassRlsRole() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first");
        seed(secondActor, secondWorkspace, "second");
        insert(firstActor, firstWorkspace, "a".repeat(64), "first.question");
        insert(secondActor, secondWorkspace, "b".repeat(64), "second.question");

        assertThat(runtime(new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT question_code FROM conversation_pending_question", String.class)))
                .containsExactly("first.question");
        assertThat(runtime(new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT question_code FROM conversation_pending_question", String.class)))
                .containsExactly("second.question");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT question_code FROM conversation_pending_question", String.class))).isEmpty();
    }

    @Test
    void pendingContextColumnsKeepExistingForcedRlsAndTypedConstraints() {
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.conversation_pending_question'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'conversation_pending_question'
                          AND column_name IN ('workflow_safe_label', 'interrupted_question_code')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactly("interrupted_question_code", "workflow_safe_label");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT conname FROM pg_constraint
                        WHERE conrelid = 'public.conversation_pending_question'::regclass
                          AND conname IN (
                            'chk_conversation_pending_question_safe_label',
                            'chk_conversation_pending_question_interrupted_code')
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "chk_conversation_pending_question_interrupted_code",
                        "chk_conversation_pending_question_safe_label");
    }

    @Test
    void routePlaceCreationDraftUsesForcedRlsAndTypedColumnsOnly() {
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.route_place_creation_draft'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT policyname FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'route_place_creation_draft'
                        """,
                        String.class))
                .containsExactly("rls_route_place_creation_actor");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT conname FROM pg_constraint
                        WHERE conrelid = 'public.route_place_creation_draft'::regclass
                          AND conname IN (
                            'chk_route_place_creation_step',
                            'chk_route_place_creation_text',
                            'chk_route_place_creation_coordinate')
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "chk_route_place_creation_coordinate",
                        "chk_route_place_creation_step",
                        "chk_route_place_creation_text");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT is_nullable || ':' || column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'route_place_creation_draft'
                          AND column_name = 'step'
                        """,
                        String.class))
                .isEqualTo("NO:'CONFIRM'::character varying");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'route_place_creation_draft'
                          AND (column_name LIKE '%raw%'
                               OR column_name LIKE '%message%'
                               OR column_name LIKE '%json%')
                        """,
                        String.class))
                .isEmpty();
    }

    @Test
    void typedScheduleDraftIsInvisibleAcrossActorAndWorkspaceUnderNoBypassRlsRole() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first typed");
        seed(secondActor, secondWorkspace, "second typed");
        insertTypedDraft(firstActor, firstWorkspace, "d".repeat(64), "first title");
        insertTypedDraft(secondActor, secondWorkspace, "e".repeat(64), "second title");

        assertThat(runtime(new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM schedule_clarification_draft", String.class)))
                .containsExactly("first title");
        assertThat(runtime(new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT title FROM schedule_clarification_draft", String.class)))
                .containsExactly("second title");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT title FROM schedule_clarification_draft", String.class))).isEmpty();
    }

    @Test
    void typedRepairDraftIsInvisibleAcrossActorAndWorkspaceUnderNoBypassRlsRole() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first repair");
        seed(secondActor, secondWorkspace, "second repair");
        insertRepairDraft(firstActor, firstWorkspace, "f".repeat(64), "TASKS_LISTED");
        insertRepairDraft(secondActor, secondWorkspace, "a".repeat(64), "SCHEDULES_LISTED");

        assertThat(runtime(new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT prior_action FROM conversation_repair_draft", String.class)))
                .containsExactly("TASKS_LISTED");
        assertThat(runtime(new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT prior_action FROM conversation_repair_draft", String.class)))
                .containsExactly("SCHEDULES_LISTED");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT prior_action FROM conversation_repair_draft", String.class))).isEmpty();
    }

    @Test
    void typedPublicPlaceDraftIsInvisibleAcrossActorAndWorkspaceUnderNoBypassRlsRole() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first place");
        seed(secondActor, secondWorkspace, "second place");
        insertPublicPlaceDraft(firstActor, firstWorkspace, "b".repeat(64), "TDX:METRO:1");
        insertPublicPlaceDraft(secondActor, secondWorkspace, "c".repeat(64), "TDX:METRO:2");

        assertThat(runtime(new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT candidate_keys FROM public_place_lookup_draft", String.class)))
                .containsExactly("TDX:METRO:1");
        assertThat(runtime(new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList(
                        "SELECT candidate_keys FROM public_place_lookup_draft", String.class)))
                .containsExactly("TDX:METRO:2");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT candidate_keys FROM public_place_lookup_draft", String.class))).isEmpty();
    }

    @Test
    void voiceProfileAndTypedDraftAreInvisibleAcrossActorsUnderForcedRls() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first voice");
        seed(secondActor, secondWorkspace, "second voice");
        insertVoiceProfile(firstActor, firstWorkspace, "小賈", "老闆");
        insertVoiceProfile(secondActor, secondWorkspace, "小秘", "主管");
        insertVoiceDraft(firstActor, firstWorkspace, "d".repeat(64), "ASSISTANT_NAME");
        insertVoiceDraft(secondActor, secondWorkspace, "e".repeat(64), "USER_ADDRESS");

        WorkspaceContext first = new WorkspaceContext(
                firstActor, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext second = new WorkspaceContext(
                secondActor, secondWorkspace, WorkspaceChannel.TEST);
        assertThat(runtime(first, () -> jdbcTemplate.queryForList(
                "SELECT assistant_self_name FROM conversation_voice_profile", String.class)))
                .containsExactly("小賈");
        assertThat(runtime(second, () -> jdbcTemplate.queryForList(
                "SELECT assistant_self_name FROM conversation_voice_profile", String.class)))
                .containsExactly("小秘");
        assertThat(runtime(first, () -> jdbcTemplate.queryForList(
                "SELECT target FROM conversation_voice_preference_draft", String.class)))
                .containsExactly("ASSISTANT_NAME");
        assertThat(runtime(second, () -> jdbcTemplate.queryForList(
                "SELECT target FROM conversation_voice_preference_draft", String.class)))
                .containsExactly("USER_ADDRESS");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT assistant_self_name FROM conversation_voice_profile", String.class)))
                .isEmpty();
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT target FROM conversation_voice_preference_draft", String.class)))
                .isEmpty();
    }

    @Test
    void typedRestaurantBookingDraftIsInvisibleAcrossActorsUnderForcedRls() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first restaurant");
        seed(secondActor, secondWorkspace, "second restaurant");
        insertRestaurantBookingDraft(firstActor, firstWorkspace, "f".repeat(64), "first restaurant");
        insertRestaurantBookingDraft(secondActor, secondWorkspace, "a".repeat(64), "second restaurant");

        WorkspaceContext first = new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext second = new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST);
        assertThat(runtime(first, () -> jdbcTemplate.queryForList(
                "SELECT restaurant_name FROM restaurant_booking_draft", String.class)))
                .containsExactly("first restaurant");
        assertThat(runtime(second, () -> jdbcTemplate.queryForList(
                "SELECT restaurant_name FROM restaurant_booking_draft", String.class)))
                .containsExactly("second restaurant");
        assertThat(runtime(WorkspaceContext.system(), () -> jdbcTemplate.queryForList(
                "SELECT restaurant_name FROM restaurant_booking_draft", String.class)))
                .isEmpty();
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
        jdbcTemplate.update("""
                INSERT INTO workspace
                    (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, label, actorId);
    }

    private void insert(UUID actorId, UUID workspaceId, String digest, String questionCode) {
        jdbcTemplate.update("""
                INSERT INTO conversation_pending_question (
                    id, channel, conversation_scope_digest, scope_key_version,
                    root_domain, workflow_id, question_code, status, revision,
                    inbound_idempotency_hmac, expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, 1, 'test', ?, ?, 'PENDING', 1, ?,
                    CURRENT_TIMESTAMP + INTERVAL '7 days',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), digest, UUID.randomUUID(), questionCode,
                "c".repeat(64), workspaceId, actorId);
    }

    private void insertTypedDraft(UUID actorId, UUID workspaceId, String digest, String title) {
        jdbcTemplate.update("""
                INSERT INTO schedule_clarification_draft (
                    id, capability, channel, conversation_scope_digest, scope_key_version,
                    title, weekday, recurrence_explicit, time_period_explicit,
                    decision_period_explicit, status, revision, expires_at,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'CONDITIONAL_RECURRENCE', 'TEST', ?, 1, ?, 'MONDAY', TRUE,
                    TRUE, FALSE, 'PENDING', 1, CURRENT_TIMESTAMP + INTERVAL '7 days',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), digest, title, workspaceId, actorId);
    }

    private void insertRepairDraft(
            UUID actorId, UUID workspaceId, String digest, String priorAction) {
        jdbcTemplate.update("""
                INSERT INTO conversation_repair_draft (
                    id, repair_kind, channel, conversation_scope_digest, scope_key_version,
                    prior_action, time_scope, repair_aspect, status, revision, expires_at,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'WRONG_ANSWER', 'TEST', ?, 1, ?, 'UPCOMING', 'UNSPECIFIED', 'PENDING', 1,
                    CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), digest, priorAction, workspaceId, actorId);
    }

    private void insertPublicPlaceDraft(
            UUID actorId, UUID workspaceId, String digest, String candidateKey) {
        jdbcTemplate.update("""
                INSERT INTO public_place_lookup_draft (
                    id, mode, channel, conversation_scope_digest, scope_key_version,
                    category, candidate_keys, status, revision, expires_at,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'READ_ONLY', 'TEST', ?, 1, 'METRO_STATION', ?, 'PENDING', 1,
                    CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, UUID.randomUUID(), digest, candidateKey, workspaceId, actorId);
    }

    private void insertVoiceProfile(
            UUID actorId, UUID workspaceId, String assistantName, String userAddress) {
        jdbcTemplate.update("""
                INSERT INTO conversation_voice_profile (
                    assistant_self_name, user_address, praise_variant_cursor,
                    dissatisfaction_variant_cursor, revision, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, -1, -1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """, assistantName, userAddress, workspaceId, actorId);
    }

    private void insertVoiceDraft(
            UUID actorId, UUID workspaceId, String digest, String target) {
        jdbcTemplate.update("""
                INSERT INTO conversation_voice_preference_draft (
                    id, channel, conversation_scope_digest, scope_key_version, target,
                    status, revision, expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, 1, ?, 'PENDING', 1,
                    CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    ?, ?)
                """, UUID.randomUUID(), digest, target, workspaceId, actorId);
    }

    private void insertRestaurantBookingDraft(
            UUID actorId, UUID workspaceId, String digest, String restaurant) {
        jdbcTemplate.update("""
                INSERT INTO restaurant_booking_draft (
                    id, channel, conversation_scope_digest, scope_key_version,
                    restaurant_name, status, revision, expires_at,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, 'TEST', ?, 1, ?, 'PENDING', 1,
                    CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    ?, ?)
                """, UUID.randomUUID(), digest, restaurant, workspaceId, actorId);
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }
}
