package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarAdoptionServiceTest extends IntegrationTestBase {

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarAdoptionService adoptions;
    @Autowired private PersonalRouteProjectionService projection;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void adoptionIsAnExplicitVersionedSelectedNodeSnapshot() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context =
                new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
        Instant time = Instant.parse("2026-07-25T02:00:00Z");
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            calendars.createPlan(new CreateCalendarPlanCommand(
                    "calendar-adoption-1",
                    "採用測試",
                    CalendarPlacement.interval(
                            time, time.plusSeconds(3600), ZoneId.of("Asia/Taipei")),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(
                            CalendarNodeDraft.of(
                                    CalendarTimeNode.absolute("selected", "已選", time)),
                            CalendarNodeDraft.of(
                                    CalendarTimeNode.absolute("not-selected", "未選", time)))));
            calendars.reviseNodeLocation(
                    "calendar-adoption-1",
                    "selected",
                    new CalendarLocation("台北", 25.033, 121.5654),
                    1);

            assertThat(adoptions.constraints()).isEmpty();
            var adopted = adoptions.adopt(
                    "calendar-adoption-1", List.of("selected"));
            assertThat(adopted.revision()).isEqualTo(1);
            assertThat(adoptions.constraints())
                    .extracting(PersonalRouteConstraint::nodeKey)
                    .containsExactly("selected");
            assertThat(projection.current().busyIntervals())
                    .containsExactly(new CalendarBusyInterval(
                            "採用測試", time, time.plusSeconds(3600)));
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_adoption_node
                        WHERE workspace_id = ?
                        """,
                        Long.class,
                        workspace))
                .isEqualTo(1L);
    }

    private void seed(UUID actorId, UUID workspaceId) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'adoption actor', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'adoption workspace', 'PERSONAL', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                actorId);
    }
}
