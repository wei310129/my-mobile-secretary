package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportArtifactService;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportProfile;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportRequest;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsExportService;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceProjectionService;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import java.time.Duration;
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
    @Autowired private CalendarRecurrenceProjectionService recurrenceProjections;
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
        long nodeRevision = jdbc.queryForObject(
                "SELECT revision FROM calendar_time_node WHERE id = ?",
                Long.class,
                fixture.nodeId());
        inContext(
                fixture.recipientContext(),
                () -> calendarReminders.createPersonalRelativeForNode(
                        fixture.planId(),
                        fixture.nodeId(),
                        nodeRevision,
                        Duration.ofMinutes(-30),
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        null));
        inContext(
                fixture.recipientContext(),
                () -> calendarReminders.createPersonalRelativeForNode(
                        fixture.planId(),
                        fixture.nodeId(),
                        nodeRevision,
                        Duration.ofHours(-2),
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        null));
        inContext(
                fixture.recipientContext(),
                () -> calendarReminders.createPersonalRelativeForNode(
                        fixture.planId(),
                        fixture.nodeId(),
                        nodeRevision,
                        Duration.ofMinutes(-10),
                        CalendarReminderDeliveryMode.ACK_REQUIRED,
                        Duration.ofMinutes(5),
                        3,
                        null));

        var artifact = inContext(
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
                .contains("BEGIN:VALARM")
                .contains("TRIGGER:-PT30M")
                .contains("TRIGGER:-PT2H")
                .doesNotContain("ATTENDEE");
        assertThat(text.split("BEGIN:VALARM", -1)).hasSize(3);
        assertThat(artifact.lossReport())
                .contains("ACK")
                .contains("retry")
                .contains("escalation")
                .contains("shared reminder templates");
    }

    @Test
    void recurringExportUsesStableOccurrenceIdentityWithoutBaseDuplication() {
        Fixture fixture = fixture();
        UUID seriesId = seedDailySeries(fixture);

        inContext(
                fixture.ownerContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "recurrence-export-a",
                        fixture.planId(),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 1),
                        CalendarIcsExportProfile.COMPACT)));
        inContext(
                fixture.ownerContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "recurrence-export-b",
                        fixture.planId(),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 1),
                        CalendarIcsExportProfile.COMPACT)));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(2)).put(anyString(), content.capture());
        assertThat(content.getAllValues().get(0))
                .isEqualTo(content.getAllValues().get(1));
        String text = new String(
                content.getAllValues().get(0),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .contains("UID:calendar-recurrence-")
                .doesNotContain("UID:calendar-plan-" + fixture.planId());
        assertThat(text.split("BEGIN:VEVENT", -1)).hasSize(3);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_recurrence_series
                        WHERE id = ?
                        """,
                        Long.class,
                        seriesId))
                .isEqualTo(1L);
    }

    @Test
    void adoptedRecurringExportReadsOnlyTheActorsRuleSnapshot() {
        Fixture fixture = fixture();
        UUID seriesId = seedDailySeries(fixture);
        grantWholePlanViewer(
                fixture,
                fixture.recipientContext(),
                "ics-recurring-adopted-viewer");
        inContext(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        UUID adoptionId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_adoption
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                UUID.class,
                fixture.planId(),
                fixture.recipientContext().workspaceId(),
                fixture.recipientContext().actorId());
        inContext(
                fixture.recipientContext(),
                () -> recurrenceProjections.adoptSeries(
                        "ics-recurring-series-adoption",
                        adoptionId,
                        fixture.planId(),
                        seriesId,
                        1));

        inContext(
                fixture.recipientContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "ics-adopted-recurring-export",
                        fixture.planId(),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 1),
                        CalendarIcsExportProfile.COMPACT)));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), content.capture());
        String text = new String(
                content.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .contains("UID:calendar-recurrence-")
                .doesNotContain("UID:calendar-plan-" + fixture.planId());
        assertThat(runtime(
                        fixture.peerContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM calendar_recurrence_adoption_rule_snapshot
                                WHERE series_id = ?
                                """,
                                Long.class,
                                seriesId)))
                .isZero();
    }

    @Test
    void recurringExportPreservesDstExceptionAndSplitRevisionProjection() {
        Fixture fixture = fixture();
        UUID seriesId = seedDstSplitSeries(fixture);

        inContext(
                fixture.ownerContext(),
                () -> calendarExports.export(new CalendarIcsExportCommand(
                        "dst-split-recurrence-export",
                        fixture.planId(),
                        LocalDate.of(2026, 3, 7),
                        LocalDate.of(2026, 3, 11),
                        CalendarIcsExportProfile.COMPACT)));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), content.capture());
        String text = new String(
                content.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .contains("DTSTART;TZID=America/New_York:20260307T090000")
                .contains("DTSTART;TZID=America/New_York:20260308T110000")
                .contains("DTSTART;TZID=America/New_York:20260309T100000")
                .contains("DTSTART;TZID=America/New_York:20260310T100000")
                .doesNotContain("UID:calendar-plan-" + fixture.planId());
        assertThat(text.split("BEGIN:VEVENT", -1)).hasSize(5);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT active_revision
                        FROM calendar_recurrence_series
                        WHERE id = ?
                        """,
                        Integer.class,
                        seriesId))
                .isEqualTo(2);
    }

    private UUID seedDailySeries(Fixture fixture) {
        UUID seriesId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_series (
                    id, plan_id, lineage_root_id, active_revision,
                    created_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                fixture.planId(),
                seriesId,
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_rule_revision (
                    series_id, revision, frequency,
                    recurrence_interval, timed_anchor,
                    duration_seconds, zone_id, end_kind,
                    occurrence_count, effective_from_timed, state,
                    request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, 1, 'DAILY', 1, ?, 3600, 'Asia/Taipei',
                    'COUNT', 3, ?, 'ACTIVE', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                java.sql.Timestamp.valueOf("2026-07-27 09:00:00"),
                java.sql.Timestamp.valueOf("2026-07-27 09:00:00"),
                "c".repeat(64),
                "d".repeat(64),
                fixture.ownerContext().actorId(),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_exception (
                    id, series_id, rule_revision, logical_timed_start,
                    kind, request_hash, payload_hash,
                    created_by_actor_id, created_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, 1, ?, 'EXCLUDED', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                UUID.randomUUID(),
                seriesId,
                java.sql.Timestamp.valueOf("2026-07-28 09:00:00"),
                "e".repeat(64),
                "f".repeat(64),
                fixture.ownerContext().actorId(),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        return seriesId;
    }

    private UUID seedDstSplitSeries(Fixture fixture) {
        UUID seriesId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_series (
                    id, plan_id, lineage_root_id, active_revision,
                    created_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, 2, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                fixture.planId(),
                seriesId,
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_rule_revision (
                    series_id, revision, frequency,
                    recurrence_interval, timed_anchor,
                    duration_seconds, zone_id, end_kind,
                    effective_from_timed, effective_until_timed, state,
                    request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, 1, 'DAILY', 1, ?, 3600, 'America/New_York',
                    'UNBOUNDED', ?, ?, 'SUPERSEDED', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                java.sql.Timestamp.valueOf("2026-03-07 09:00:00"),
                java.sql.Timestamp.valueOf("2026-03-07 09:00:00"),
                java.sql.Timestamp.valueOf("2026-03-09 00:00:00"),
                "1".repeat(64),
                "2".repeat(64),
                fixture.ownerContext().actorId(),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_rule_revision (
                    series_id, revision, frequency,
                    recurrence_interval, timed_anchor,
                    duration_seconds, zone_id, end_kind,
                    effective_from_timed, state,
                    request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, 2, 'DAILY', 1, ?, 3600, 'America/New_York',
                    'UNBOUNDED', ?, 'ACTIVE', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                java.sql.Timestamp.valueOf("2026-03-09 10:00:00"),
                java.sql.Timestamp.valueOf("2026-03-09 10:00:00"),
                "3".repeat(64),
                "4".repeat(64),
                fixture.ownerContext().actorId(),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_exception (
                    id, series_id, rule_revision, logical_timed_start,
                    kind, placement_kind, timed_start, timed_end, zone_id,
                    request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, ?, 1, ?, 'OVERRIDDEN', 'TIMED_INTERVAL',
                    ?, ?, 'America/New_York', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                UUID.randomUUID(),
                seriesId,
                java.sql.Timestamp.valueOf("2026-03-08 09:00:00"),
                java.sql.Timestamp.from(
                        java.time.ZonedDateTime
                                .of(
                                        2026,
                                        3,
                                        8,
                                        11,
                                        0,
                                        0,
                                        0,
                                        java.time.ZoneId.of(
                                                "America/New_York"))
                                .toInstant()),
                java.sql.Timestamp.from(
                        java.time.ZonedDateTime
                                .of(
                                        2026,
                                        3,
                                        8,
                                        12,
                                        0,
                                        0,
                                        0,
                                        java.time.ZoneId.of(
                                                "America/New_York"))
                                .toInstant()),
                "5".repeat(64),
                "6".repeat(64),
                fixture.ownerContext().actorId(),
                fixture.ownerContext().workspaceId(),
                fixture.ownerContext().actorId());
        return seriesId;
    }
}
