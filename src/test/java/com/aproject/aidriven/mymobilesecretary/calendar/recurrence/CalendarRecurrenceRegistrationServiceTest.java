package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarRecurrenceRegistrationServiceTest extends IntegrationTestBase {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Instant START = Instant.parse("2026-08-01T06:00:00Z");

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarRecurrenceRegistrationService recurrences;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void registersAndReplaysOneWeeklyOwnerSeriesWithAnInclusiveCutoff() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context =
                new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
        CalendarPlacement placement =
                CalendarPlacement.interval(START, START.plus(Duration.ofHours(2)), TAIPEI);
        UUID planId = inContext(context, () -> calendars
                .createPlanWithIdentity(new CreateCalendarPlanCommand(
                        "recurrence-plan-1",
                        "陶藝課",
                        placement,
                        "學習",
                        null,
                        null,
                        List.of(),
                        List.of()))
                .planId());

        UUID first = inContext(context, () -> recurrences.registerPlan(
                planId,
                placement,
                "WEEKLY",
                LocalDate.parse("2026-09-30"),
                "recurrence-request-1"));
        UUID replay = inContext(context, () -> recurrences.registerPlan(
                planId,
                placement,
                "WEEKLY",
                LocalDate.parse("2026-09-30"),
                "recurrence-request-1"));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_recurrence_series WHERE plan_id = ?",
                        Long.class,
                        planId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_recurrence_rule_revision
                        WHERE series_id = ?
                        """,
                        Long.class,
                        first))
                .isEqualTo(1L);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT frequency, recurrence_interval, weekdays::text,
                               duration_seconds, end_kind, until_timed
                        FROM calendar_recurrence_rule_revision
                        WHERE series_id = ?
                        """,
                        first))
                .containsEntry("frequency", "WEEKLY")
                .containsEntry("recurrence_interval", 1)
                .containsEntry("weekdays", "{6}")
                .containsEntry("duration_seconds", 7200L)
                .containsEntry("end_kind", "UNTIL_TIMED");
    }

    @Test
    void conflictingReplayFailsClosedWithoutASecondRevision() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context =
                new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
        CalendarPlacement placement = CalendarPlacement.point(START, TAIPEI);
        UUID planId = inContext(context, () -> calendars
                .createPlanWithIdentity(new CreateCalendarPlanCommand(
                        "recurrence-plan-2",
                        "固定複習",
                        placement,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()))
                .planId());
        inContext(context, () -> recurrences.registerPlan(
                planId, placement, "WEEKLY", null, "recurrence-request-2"));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> recurrences.registerPlan(
                                planId,
                                placement,
                                "DAILY",
                                null,
                                "recurrence-request-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different semantics");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_recurrence_rule_revision
                        WHERE series_id IN (
                            SELECT id FROM calendar_recurrence_series WHERE plan_id = ?)
                        """,
                        Long.class,
                        planId))
                .isEqualTo(1L);
    }

    private void seed(UUID actor, UUID workspace) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'recurrence owner', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'recurrence workspace', 'PERSONAL', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
    }

    private static <T> T inContext(
            WorkspaceContext context, Supplier<T> operation) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return operation.get();
        }
    }
}
