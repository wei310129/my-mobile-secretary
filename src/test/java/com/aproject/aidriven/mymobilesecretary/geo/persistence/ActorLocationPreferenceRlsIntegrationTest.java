package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ActorLocationPreferenceRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_actor_location_rls_runtime";

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
                        WHERE rolname = 'mms_actor_location_rls_runtime') THEN
                        CREATE ROLE mms_actor_location_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void preferenceIsInvisibleAcrossActorsAndWorkspacesUnderForcedRls() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seedUser(owner, "owner");
        seedUser(peer, "peer");
        seedWorkspace(workspace, owner, "owner workspace");
        seedWorkspace(otherWorkspace, peer, "peer workspace");
        Long ownerPlace = seedPlace(workspace, owner, "owner home", 25.033, 121.5654);
        Long peerPlace = seedPlace(otherWorkspace, peer, "peer home", 24.1477, 120.6736);
        insertPreference(workspace, owner, ownerPlace);
        insertPreference(otherWorkspace, peer, peerPlace);

        WorkspaceContext ownerContext = context(owner, workspace);
        WorkspaceContext peerContext = context(peer, otherWorkspace);
        assertThat(runtime(ownerContext, this::visiblePlaceIds)).containsExactly(ownerPlace);
        assertThat(runtime(peerContext, this::visiblePlaceIds)).containsExactly(peerPlace);
        assertThat(runtime(WorkspaceContext.system(), this::visiblePlaceIds)).isEmpty();
        assertThat(runtime(peerContext, () -> jdbc.update(
                        "UPDATE actor_location_preference SET active = FALSE WHERE place_id = ?",
                        ownerPlace)))
                .isZero();
    }

    @Test
    void migrationForcesRlsAndKeepsOwnedPlaceAndTypedUniquenessConstraints() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.actor_location_preference'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbc.queryForList(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'actor_location_preference'
                        ORDER BY policyname
                        """,
                        String.class))
                .containsExactly("rls_actor_location_preference_actor=ALL");
        assertThat(jdbc.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'public.actor_location_preference'::regclass
                          AND conname IN (
                            'fk_actor_location_preference_owned_place',
                            'uq_actor_location_preference_kind')
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "fk_actor_location_preference_owned_place",
                        "uq_actor_location_preference_kind");
    }

    private List<Long> visiblePlaceIds() {
        return jdbc.queryForList(
                "SELECT place_id FROM actor_location_preference ORDER BY place_id", Long.class);
    }

    private void insertPreference(UUID workspace, UUID actor, Long placeId) {
        jdbc.update(
                """
                INSERT INTO actor_location_preference (
                    location_kind, place_id, active, revision, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES ('HOME', ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                placeId,
                workspace,
                actor);
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private Long seedPlace(UUID workspace, UUID actor, String name, double latitude, double longitude) {
        return jdbc.queryForObject(
                """
                INSERT INTO place (
                    name, address, latitude, longitude, type, created_at,
                    workspace_id, created_by_user_id)
                VALUES (?, NULL, ?, ?, 'HOME', CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """,
                Long.class,
                name,
                latitude,
                longitude,
                workspace,
                actor);
    }

    private void seedUser(UUID actor, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor,
                label + actor.toString().substring(0, 6));
    }

    private void seedWorkspace(UUID workspace, UUID actor, String label) {
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                label,
                actor);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST, "home-rls", "scope");
    }
}
