package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Duration;
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
    @Autowired private CalendarRouteRiskService routeRisks;
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

    @Test
    void routeRiskNotificationIsDurableDebouncedAndRevisionConfirmed() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context =
                new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
        Instant firstTime = Instant.parse("2026-07-25T02:00:00Z");
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            calendars.createPlan(new CreateCalendarPlanCommand(
                    "calendar-route-risk-1",
                    "跨區行程",
                    CalendarPlacement.interval(
                            firstTime,
                            firstTime.plus(Duration.ofHours(2)),
                            ZoneId.of("Asia/Taipei")),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(
                            CalendarNodeDraft.of(CalendarTimeNode.absolute(
                                    "first", "前一站", firstTime)),
                            CalendarNodeDraft.of(CalendarTimeNode.absolute(
                                    "second",
                                    "下一站",
                                    firstTime.plus(Duration.ofHours(1)))))));
            calendars.reviseNodeLocation(
                    "calendar-route-risk-1",
                    "first",
                    new CalendarLocation("淡水", 25.1676, 121.445),
                    1);
            calendars.reviseNodeLocation(
                    "calendar-route-risk-1",
                    "second",
                    new CalendarLocation("新店", 24.9676, 121.5415),
                    1);
            adoptions.adopt(
                    "calendar-route-risk-1", List.of("first", "second"));
            List<PersonalRouteConstraint> constraints = adoptions.constraints();
            PersonalRouteConstraint from = constraints.get(0);
            PersonalRouteConstraint to = constraints.get(1);

            PersonalRouteAssessment first = risk(
                    from, to, Duration.ofMinutes(90));
            var created = routeRisks.observe(first);
            assertThat(created.revision()).isEqualTo(1);
            assertThat(created.shouldNotify()).isTrue();

            var replay = routeRisks.observe(first);
            assertThat(replay.revision()).isEqualTo(1);
            assertThat(replay.shouldNotify()).isFalse();

            var improved = routeRisks.observe(
                    risk(from, to, Duration.ofMinutes(80)));
            assertThat(improved.revision()).isEqualTo(1);
            assertThat(improved.shouldNotify()).isFalse();

            var worsened = routeRisks.observe(
                    risk(from, to, Duration.ofMinutes(91)));
            assertThat(worsened.revision()).isEqualTo(2);
            assertThat(worsened.shouldNotify()).isTrue();

            var confirmed = routeRisks.confirm(
                    worsened.riskId(), worsened.revision());
            assertThat(confirmed.status()).isEqualTo("CONFIRMED");
            assertThat(routeRisks.confirm(
                            worsened.riskId(), worsened.revision()).status())
                    .isEqualTo("CONFIRMED");
            assertThatThrownBy(() -> routeRisks.confirm(worsened.riskId(), 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("changed before confirmation");

            var acknowledgedReplay = routeRisks.observe(
                    risk(from, to, Duration.ofMinutes(91)));
            assertThat(acknowledgedReplay.status()).isEqualTo("CONFIRMED");
            assertThat(acknowledgedReplay.shouldNotify()).isFalse();

            routeRisks.resolve(new PersonalRouteAssessment(
                    PersonalRouteStatus.FEASIBLE,
                    from,
                    to,
                    Duration.ofMinutes(40),
                    Duration.ofHours(1)));
            var reopened = routeRisks.observe(
                    risk(from, to, Duration.ofMinutes(91)));
            assertThat(reopened.revision()).isEqualTo(4);
            assertThat(reopened.shouldNotify()).isTrue();
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_route_risk
                        WHERE workspace_id = ? AND created_by_user_id = ?
                        """,
                        Long.class,
                        workspace,
                        actor))
                .isEqualTo(1L);
    }

    private static PersonalRouteAssessment risk(
            PersonalRouteConstraint from,
            PersonalRouteConstraint to,
            Duration required) {
        return new PersonalRouteAssessment(
                PersonalRouteStatus.IMPOSSIBLE,
                from,
                to,
                required,
                Duration.between(from.effectiveTime(), to.effectiveTime()));
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
