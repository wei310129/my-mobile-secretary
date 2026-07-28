package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CalendarEditorMigrationContractTest {

    @Test
    void v86SeparatesCoreEditorFromAuthoritativeCapability()
            throws IOException {
        String migration = new ClassPathResource(
                        "db/migration/V86__create_calendar_editor_capability.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("permission IN ('VIEWER', 'EDITOR')")
                .contains("calendar_authoritative_editor_capability")
                .contains("calendar_share_role_audit")
                .contains("calendar_authoritative_mutation_audit")
                .contains("'ROLE_CHANGED'")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY");
    }

    @Test
    void v86InstallsScopedUpdateAndColumnGuardBoundaries()
            throws IOException {
        String migration = new ClassPathResource(
                        "db/migration/V86__create_calendar_editor_capability.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("rls_calendar_time_node_editor_update")
                .contains("guard_calendar_editor_node_update")
                .contains("app.calendar_editor_mutation_id")
                .contains("calendar_editor_mutation_audit")
                .contains("append-only");
    }
}
