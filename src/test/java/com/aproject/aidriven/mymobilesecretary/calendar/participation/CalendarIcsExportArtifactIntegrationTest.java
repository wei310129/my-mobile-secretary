package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportArtifactService;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportProfile;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportRequest;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportService;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CalendarIcsExportArtifactIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarIcsExportArtifactService exports;
    @Autowired private CalendarIcsExportService calendarExports;
    @MockitoBean private MediaObjectStorage storage;

    @Test
    void artifactIsActorPrivateAcknowledgedAndConsumedExactlyOnce() {
        Fixture fixture = fixture();
        byte[] content = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes();
        var artifact = inContext(
                fixture.ownerContext(),
                () -> exports.create(new CalendarIcsExportRequest(
                        "export-owner",
                        fixture.planId(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2),
                        CalendarIcsExportProfile.COMPACT,
                        "ACK and shared reminders are not preserved",
                        content)));
        when(storage.read(anyString())).thenReturn(content);

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> exports.download(artifact.token(), false)))
                .isInstanceOf(SecurityException.class);
        verify(storage, never()).read(anyString());
        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> exports.download(artifact.token(), true)))
                .isInstanceOf(SecurityException.class);

        assertThat(runtime(
                        fixture.ownerContext(),
                        () -> exports.download(artifact.token(), true)))
                .isEqualTo(content);
        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> exports.download(artifact.token(), true)))
                .isInstanceOf(SecurityException.class);
        verify(storage).read(anyString());
    }

    @Test
    void expiryRevocationReplayAndOversizedWindowFailClosed() {
        Fixture fixture = fixture();
        byte[] content = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes();
        CalendarIcsExportRequest request = new CalendarIcsExportRequest(
                "export-lifecycle",
                fixture.planId(),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                CalendarIcsExportProfile.COMPACT,
                "loss",
                content);
        var artifact = inContext(
                fixture.ownerContext(), () -> exports.create(request));
        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(), () -> exports.create(request)))
                .isInstanceOf(SecurityException.class);

        jdbc.update(
                """
                UPDATE calendar_ics_export_artifact
                SET artifact_status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                artifact.id());
        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> exports.download(artifact.token(), true)))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> exports.create(new CalendarIcsExportRequest(
                                "export-too-wide",
                                fixture.planId(),
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2027, 1, 3),
                                CalendarIcsExportProfile.COMPACT,
                                "loss",
                                content))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).read(anyString());
    }

    @Test
    void compactExportBuildsContentOnlyFromTheAuthorizedPlanProjection() {
        Fixture fixture = fixture();
        UUID activityId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_activity (
                    id, plan_id, title, placement_kind,
                    all_day_start, all_day_end_exclusive,
                    version, created_at, updated_at, workspace_id,
                    created_by_user_id)
                VALUES (?, ?, '靠港日', 'ALL_DAY', ?, ?, 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                activityId,
                fixture.planId(),
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 7, 28),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());

        var artifact = inContext(
                fixture.ownerContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "compact-owner-export",
                        fixture.planId(),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 7, 28),
                        CalendarIcsExportProfile.COMPACT)));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), content.capture());
        String text = new String(
                content.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .contains("SUMMARY:群組登船行程")
                .contains("DESCRIPTION:登船")
                .contains("UID:calendar-activity-" + activityId)
                .contains("DTSTART;VALUE=DATE:20260727")
                .doesNotContain("ATTENDEE");
        assertThat(artifact.lossReport())
                .contains("ACK")
                .contains("shared reminder templates");
    }

    @Test
    void routeAwareSharedExportUsesOnlyTheActorsAdoptedSnapshot() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture,
                fixture.recipientContext(),
                "ics-adopted-viewer");
        inContext(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));

        inContext(
                fixture.recipientContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "route-aware-adopted-export",
                        fixture.planId(),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 7, 28),
                        CalendarIcsExportProfile.ROUTE_AWARE)));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), content.capture());
        String text = new String(
                content.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .contains("SUMMARY:群組登船行程")
                .contains("UID:calendar-route-node-")
                .contains("SUMMARY:登船")
                .doesNotContain("ATTENDEE");
    }
}
