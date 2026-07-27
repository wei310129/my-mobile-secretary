package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarEditorRoleContractTest {

    @Test
    void viewerAndEditorAreCorePermissionsButAuthoritativeIsSeparate() {
        assertThat(CalendarSharePermission.values())
                .containsExactly(
                        CalendarSharePermission.VIEWER,
                        CalendarSharePermission.EDITOR);
        assertThatThrownBy(() ->
                        CalendarSharePermission.valueOf("AUTHORITATIVE_EDITOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleChangeRequiresBoundedRequestAndExpectedShareRevision() {
        UUID shareId = UUID.randomUUID();

        CalendarShareRoleChange change = new CalendarShareRoleChange(
                "role-change",
                shareId,
                CalendarSharePermission.EDITOR,
                3);

        assertThat(change.requestKey()).isEqualTo("role-change");
        assertThat(change.shareId()).isEqualTo(shareId);
        assertThat(change.permission())
                .isEqualTo(CalendarSharePermission.EDITOR);
        assertThat(change.expectedShareRevision()).isEqualTo(3);
    }

    @Test
    void roleChangeRejectsBlankRequestAndNonPositiveRevision() {
        assertThatThrownBy(() -> new CalendarShareRoleChange(
                        " ",
                        UUID.randomUUID(),
                        CalendarSharePermission.EDITOR,
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarShareRoleChange(
                        "role-change",
                        UUID.randomUUID(),
                        CalendarSharePermission.EDITOR,
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
