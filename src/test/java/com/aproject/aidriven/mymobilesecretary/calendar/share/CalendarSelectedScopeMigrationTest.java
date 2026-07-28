package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CalendarSelectedScopeMigrationTest {

    @Test
    void v85DefinesVersionedSelectedScopeAndSemanticReceipts() throws IOException {
        String migration = new ClassPathResource(
                        "db/migration/V85__create_calendar_selected_share_scope.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("calendar_share_scope_snapshot")
                .contains("calendar_share_scope_item")
                .contains("calendar_share_request_receipt")
                .contains("scope_revision")
                .contains("current_scope_snapshot_id")
                .contains("semantic_fingerprint")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("trg_calendar_share_scope_complete")
                .contains("trg_calendar_share_scope_snapshot_immutable")
                .contains("trg_calendar_share_scope_item_immutable")
                .contains("trg_calendar_share_scope_item_insert_guard")
                .contains("NO FORCE ROW LEVEL SECURITY")
                .contains("DISABLE ROW LEVEL SECURITY")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY");
    }

    @Test
    void v85KeepsDependencyMinimumOutOfFullNodeSelectPolicy() throws IOException {
        String migration = new ClassPathResource(
                        "db/migration/V85__create_calendar_selected_share_scope.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("dependency_minimum")
                .contains("dependency_resolved_time")
                .contains("dependency_revision")
                .contains("dependency_minimum = FALSE");
    }

    @Test
    void v85RemovesTheSingleActiveRecipientConstraintForAdditiveGrants()
            throws IOException {
        String migration = new ClassPathResource(
                        "db/migration/V85__create_calendar_selected_share_scope.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("DROP INDEX uq_calendar_share_active_recipient")
                .contains("uq_calendar_share_active_semantic");
    }
}
