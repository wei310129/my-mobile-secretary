package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsImportConfirmCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsImportService;
import com.aproject.aidriven.mymobilesecretary.calendar.ics.CalendarIcsImportUploadRequest;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CalendarIcsImportIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarIcsImportService imports;
    @MockitoBean private MediaObjectStorage storage;

    @Test
    void uploadCreatesOnlyPrivatePreviewAndDeduplicatesScopedFile() {
        Fixture fixture = fixture();
        byte[] content = twoEventCalendar();
        long plansBefore = count("calendar_plan", fixture);
        long remindersBefore = count("calendar_reminder_rule", fixture);
        long registrationsBefore =
                count("calendar_recurring_registration", fixture);
        long sharesBefore = count("calendar_share", fixture);

        var first = inContext(
                fixture.ownerContext(),
                () -> imports.upload(new CalendarIcsImportUploadRequest(
                        "upload-two-events",
                        "calendar.ics",
                        content)));
        var replay = inContext(
                fixture.ownerContext(),
                () -> imports.upload(new CalendarIcsImportUploadRequest(
                        "upload-two-events-replay",
                        "calendar.ics",
                        content)));

        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.items()).hasSize(2);
        assertThat(first.items().getFirst().title()).isEqualTo("Boarding");
        assertThat(first.items().getFirst().reminderOffsetSeconds())
                .containsExactly(1800L);
        assertThat(first.items().get(1).recurrenceSupported()).isFalse();
        assertThat(first.items().get(1).state()).isEqualTo("UNSUPPORTED");
        assertThat(count("calendar_plan", fixture)).isEqualTo(plansBefore);
        assertThat(count("calendar_reminder_rule", fixture))
                .isEqualTo(remindersBefore);
        assertThat(count("calendar_recurring_registration", fixture))
                .isEqualTo(registrationsBefore);
        assertThat(count("calendar_share", fixture))
                .isEqualTo(sharesBefore);
        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> imports.preview(first.id())))
                .isInstanceOf(SecurityException.class);
        verify(storage, times(1)).put(anyString(), any(byte[].class));
    }

    @Test
    void revisionBoundConfirmationMaterializesOnlyQuotedItemExactlyOnce() {
        Fixture fixture = fixture();
        var batch = inContext(
                fixture.ownerContext(),
                () -> imports.upload(new CalendarIcsImportUploadRequest(
                        "upload-confirm",
                        "confirmation.ics",
                        twoEventCalendar())));
        var item = batch.items().getFirst();
        long planCount = count("calendar_plan", fixture);
        long reminderCount = count("calendar_reminder_rule", fixture);
        var command = new CalendarIcsImportConfirmCommand(
                "confirm-quoted-item",
                item.id(),
                item.revision(),
                true,
                true,
                null);

        var created = inContext(
                fixture.ownerContext(), () -> imports.confirm(command));
        var replay = inContext(
                fixture.ownerContext(), () -> imports.confirm(command));
        var after = inContext(
                fixture.ownerContext(), () -> imports.preview(batch.id()));

        assertThat(replay.planId()).isEqualTo(created.planId());
        assertThat(count("calendar_plan", fixture)).isEqualTo(planCount + 1);
        assertThat(count("calendar_reminder_rule", fixture))
                .isEqualTo(reminderCount);
        assertThat(after.items().getFirst().state())
                .isEqualTo("MATERIALIZED");
        assertThat(after.items().get(1).materializedPlanId()).isNull();
    }

    @Test
    void floatingTimeRequiresExplicitJavaZoneBeforeMaterialization() {
        Fixture fixture = fixture();
        byte[] content = """
                BEGIN:VCALENDAR\r
                BEGIN:VEVENT\r
                UID:floating-confirm\r
                DTSTART:20260731T090000\r
                DTEND:20260731T100000\r
                SUMMARY:Floating event\r
                END:VEVENT\r
                END:VCALENDAR\r
                """
                .getBytes(StandardCharsets.UTF_8);
        var batch = inContext(
                fixture.ownerContext(),
                () -> imports.upload(new CalendarIcsImportUploadRequest(
                        "upload-floating",
                        "floating.ics",
                        content)));
        var item = batch.items().getFirst();
        long planCount = count("calendar_plan", fixture);

        assertThat(item.floatingTime()).isTrue();
        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> imports.confirm(
                                new CalendarIcsImportConfirmCommand(
                                        "confirm-floating-missing-zone",
                                        item.id(),
                                        item.revision(),
                                        true,
                                        true,
                                        null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZoneId");
        assertThat(count("calendar_plan", fixture)).isEqualTo(planCount);

        var created = inContext(
                fixture.ownerContext(),
                () -> imports.confirm(new CalendarIcsImportConfirmCommand(
                        "confirm-floating-with-zone",
                        item.id(),
                        item.revision(),
                        true,
                        true,
                        ZoneId.of("Asia/Taipei"))));
        assertThat(created.title()).isEqualTo("Floating event");
        assertThat(count("calendar_plan", fixture))
                .isEqualTo(planCount + 1);
    }

    private long count(String table, Fixture fixture) {
        return runtime(
                fixture.ownerContext(),
                () -> jdbc.queryForObject(
                        "SELECT count(*) FROM " + table,
                        Long.class));
    }

    private static byte[] twoEventCalendar() {
        return """
                BEGIN:VCALENDAR\r
                VERSION:2.0\r
                METHOD:REQUEST\r
                BEGIN:VEVENT\r
                UID:event-a@example.test\r
                SEQUENCE:2\r
                DTSTART;TZID=Asia/Taipei:20260731T090000\r
                DTEND;TZID=Asia/Taipei:20260731T100000\r
                SUMMARY:Boarding\r
                ORGANIZER:mailto:owner@example.test\r
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:member@example.test\r
                URL:https://metadata.invalid/latest\r
                BEGIN:VALARM\r
                TRIGGER:-PT30M\r
                END:VALARM\r
                END:VEVENT\r
                BEGIN:VEVENT\r
                UID:event-b@example.test\r
                DTSTART;VALUE=DATE:20260801\r
                DTEND;VALUE=DATE:20260802\r
                SUMMARY:Unsupported repeat\r
                RRULE:FREQ=HOURLY;COUNT=10\r
                ATTACH:https://metadata.invalid/secret\r
                END:VEVENT\r
                END:VCALENDAR\r
                """
                .getBytes(StandardCharsets.UTF_8);
    }
}
