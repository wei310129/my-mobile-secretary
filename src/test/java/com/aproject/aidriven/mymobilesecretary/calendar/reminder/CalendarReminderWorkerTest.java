package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderPreferenceService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarReminderWorkerTest extends IntegrationTestBase {

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarReminderApplicationService reminders;
    @Autowired private CalendarReminderOccurrenceWorker worker;
    @Autowired private ReminderPreferenceService preferences;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void retryAndRestartCreateExactlyOneVisibleOutboxDelivery() {
        WorkspaceContext context = new WorkspaceContext(
                LegacyAccountIds.USER_ID,
                LegacyAccountIds.WORKSPACE_ID,
                WorkspaceChannel.TEST);
        Instant due = Instant.now().minusSeconds(30);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            calendars.createPlan(new CreateCalendarPlanCommand(
                    "calendar-reminder-worker",
                    "該出門了",
                    CalendarPlacement.point(due, ZoneId.of("Asia/Taipei")),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(CalendarNodeDraft.of(
                            CalendarTimeNode.absolute("departure", "出發", due)))));
            reminders.createRelative(
                    "calendar-reminder-worker",
                    "departure",
                    Duration.ZERO,
                    CalendarReminderOwnerKind.PERSONAL,
                    CalendarReminderDeliveryMode.ONCE,
                    null,
                    null,
                    NotificationChannel.LOG);
        }

        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            worker.process(LegacyAccountIds.USER_ID);
            worker.process(LegacyAccountIds.USER_ID);
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM notification_outbox
                        WHERE delivery_key LIKE 'calendar-reminder:%'
                        """,
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM notification_outbox
                        WHERE delivery_key LIKE 'calendar-reminder:%'
                            AND channel = 'LOG'
                        """,
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_occurrence
                        WHERE status = 'ENQUEUED'
                        """,
                        Long.class))
                .isEqualTo(1L);
    }

    @Test
    void quietHoursDeferTheSameOccurrenceInsteadOfDroppingIt() {
        WorkspaceContext context = new WorkspaceContext(
                LegacyAccountIds.USER_ID,
                LegacyAccountIds.WORKSPACE_ID,
                WorkspaceChannel.TEST);
        Instant now = Instant.now();
        Instant resumeAt = now.plus(Duration.ofHours(1));
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            calendars.createPlan(new CreateCalendarPlanCommand(
                    "calendar-reminder-muted",
                    "勿擾測試",
                    CalendarPlacement.point(
                            now.minusSeconds(10), ZoneId.of("Asia/Taipei")),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                            "muted-node", "稍後提醒", now.minusSeconds(10))))));
            reminders.createRelative(
                    "calendar-reminder-muted",
                    "muted-node",
                    Duration.ZERO,
                    CalendarReminderOwnerKind.PERSONAL,
                    CalendarReminderDeliveryMode.ONCE,
                    null,
                    null,
                    NotificationChannel.LOG);
            preferences.muteUntil(resumeAt);
            worker.process(LegacyAccountIds.USER_ID);
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM notification_outbox
                        WHERE delivery_key LIKE 'calendar-reminder:%'
                        """,
                        Long.class))
                .isZero();
        Instant deferred = jdbc.queryForObject(
                """
                SELECT scheduled_at FROM calendar_reminder_occurrence
                WHERE status = 'PENDING'
                """,
                Instant.class);
        assertThat(deferred)
                .isAfterOrEqualTo(resumeAt.truncatedTo(ChronoUnit.MICROS));
    }
}
