package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelTimeEstimator;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarIntentDraftRoutePreflightActualEntryTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intents;
    @Autowired private CalendarApplicationService calendars;
    @Autowired private PlaceService places;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private TravelTimeEstimator travelTimes;

    @Test
    void ordinaryCreatePausesForRiskThenConfirmsOnePlanAndDurableRisk() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context = new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST, "h7-entry", "conversation");
        when(travelTimes.estimateEvidence(
                        anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                        Duration.ofMinutes(30),
                        TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            places.createPlace("既有地點", null, 25.03, 121.56, "OFFICE");
            places.createPlace("候選地點", null, 25.17, 121.44, "VENUE");
            CalendarLocation existingLocation = new CalendarLocation(
                    "既有地點", 25.03, 121.56);
            calendars.createPlan(new CreateCalendarPlanCommand(
                    "h7-existing",
                    "前一場會議",
                    CalendarPlacement.interval(
                            Instant.parse("2026-09-20T05:00:00Z"),
                            Instant.parse("2026-09-20T06:50:00Z"),
                            ZoneId.of("Asia/Taipei")),
                    "工作",
                    null,
                    null,
                    List.of(),
                    List.of(
                            CalendarNodeDraft.at(
                                    CalendarTimeNode.absolute(
                                            "start",
                                            "前一場開始",
                                            Instant.parse("2026-09-20T05:00:00Z")),
                                    existingLocation),
                            CalendarNodeDraft.at(
                                    CalendarTimeNode.absolute(
                                            "end",
                                            "前一場結束",
                                            Instant.parse("2026-09-20T06:50:00Z")),
                                    existingLocation))));

            String createText = "9月20日下午三點到四點在候選地點開會";
            IntentCommand create = new IntentCommand(
                    IntentCommand.Type.CREATE_SCHEDULE,
                    "候選會議",
                    null,
                    "2026-09-20T15:00:00+08:00",
                    "2026-09-20T16:00:00+08:00",
                    "候選地點",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    IntentOptions.empty(),
                    createText);
            interpreter.nextCommand(create);

            IntentResult risk = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(createText, "TEST"));

            assertThat(risk.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            assertThat(risk.responseEnvelope().message())
                    .contains("只有 10 分鐘", "需要約 30 分鐘", "要照原安排保留嗎")
                    .doesNotContain("Intent", "reason=", "planId", "nodeId", "revision:");
            assertThat(count("calendar_plan", workspace)).isEqualTo(1L);
            assertThat(count("calendar_intent_draft", workspace)).isEqualTo(1L);
            assertThat(count("calendar_route_risk", workspace)).isZero();
            assertThat(jdbc.queryForObject(
                            """
                            SELECT revision FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            Long.class,
                            workspace))
                    .isEqualTo(2L);

            String confirmText = "風險我知道，照原安排建立";
            IntentCommand confirm = contextCommand(confirmText);
            UUID confirmRequest = UUID.randomUUID();
            interpreter.nextCommand(confirm);
            IntentResult confirmed = RequestCorrelationContext.run(
                    confirmRequest, () -> intents.handle(confirmText, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    confirmRequest, () -> intents.handle(confirmText, "TEST"));

            assertThat(replay.message()).isEqualTo(confirmed.message());
            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(count("calendar_plan", workspace)).isEqualTo(2L);
            assertThat(count("calendar_intent_draft", workspace)).isEqualTo(1L);
            assertThat(count("calendar_route_risk", workspace)).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_route_risk
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            workspace))
                    .isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            workspace))
                    .isEqualTo("MATERIALIZED");
        }
    }

    @Test
    void ordinaryCreateWithFeasibleAdjacentRouteMaterializesImmediately() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context = new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST, "h7-feasible", "conversation");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            places.createPlace("同一地點", null, 25.03, 121.56, "OFFICE");
            String createText = "9月21日下午三點到四點在同一地點開會";
            IntentCommand create = scheduleCommand(
                    "可行會議",
                    "2026-09-21T15:00:00+08:00",
                    "2026-09-21T16:00:00+08:00",
                    "同一地點",
                    createText);
            interpreter.nextCommand(create);

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(createText, "TEST"));

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.responseEnvelope().message())
                    .contains("已建立行程", "可行會議")
                    .doesNotContain("Intent", "reason=", "planId", "nodeId", "revision:");
            assertThat(count("calendar_plan", workspace)).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            workspace))
                    .isEqualTo("MATERIALIZED");
        }
    }

    @Test
    void correctionClearsOldRiskApprovalAndReassessesTheNewRevision() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context = new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST, "h7-correction", "conversation");
        when(travelTimes.estimateEvidence(
                        anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                        Duration.ofMinutes(30),
                        TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            createExistingPlanEndingAt(
                    "修正前地點", 25.03, 121.56, Instant.parse("2026-09-22T06:50:00Z"));
            places.createPlace("修正後地點", null, 25.17, 121.44, "VENUE");
            String createText = "9月22日下午三點到四點在修正後地點開會";
            interpreter.nextCommand(scheduleCommand(
                    "待修正會議",
                    "2026-09-22T15:00:00+08:00",
                    "2026-09-22T16:00:00+08:00",
                    "修正後地點",
                    createText));
            IntentResult risk = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(createText, "TEST"));
            assertThat(risk.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);

            String correctionText = "改成下午三點半到四點半";
            IntentCommand correction = new IntentCommand(
                    IntentCommand.Type.RESCHEDULE_SCHEDULE,
                    null,
                    null,
                    "2026-09-22T15:30:00+08:00",
                    "2026-09-22T16:30:00+08:00",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    IntentOptions.empty(),
                    correctionText);
            interpreter.nextCommand(correction);
            IntentResult corrected = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(correctionText, "TEST"));

            assertThat(corrected.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(count("calendar_plan", workspace)).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT route_preflight_status IS NULL
                                   AND route_preflight_hash IS NULL
                            FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            Boolean.class,
                            workspace))
                    .isTrue();

            String confirmText = "照修正後的時間建立";
            interpreter.nextCommand(contextCommand(confirmText));
            IntentResult confirmed = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(confirmText, "TEST"));

            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(count("calendar_plan", workspace)).isEqualTo(2L);
            assertThat(count("calendar_route_risk", workspace)).isZero();
        }
    }

    @Test
    void missingRouteEvidenceRequiresExplicitConfirmationWithoutClaimingFeasibility() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace);
        WorkspaceContext context = new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST, "h7-insufficient", "conversation");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            createExistingPlanEndingAt(
                    "已有地點", 25.03, 121.56, Instant.parse("2026-09-23T06:50:00Z"));
            String createText = "9月23日下午三點到四點開會";
            interpreter.nextCommand(scheduleCommand(
                    "未知地點會議",
                    "2026-09-23T15:00:00+08:00",
                    "2026-09-23T16:00:00+08:00",
                    null,
                    createText));

            IntentResult insufficient = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(createText, "TEST"));

            assertThat(insufficient.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            assertThat(insufficient.responseEnvelope().message())
                    .contains("缺可靠路線資料", "不能判定是否趕得上", "仍照這個版本建立嗎")
                    .doesNotContain("可行", "準時", "Intent", "reason=", "planId", "nodeId");
            assertThat(count("calendar_plan", workspace)).isEqualTo(1L);
            assertThat(count("calendar_route_risk", workspace)).isZero();
        }
    }

    private void createExistingPlanEndingAt(
            String placeName, double latitude, double longitude, Instant end) {
        places.createPlace(placeName, null, latitude, longitude, "OFFICE");
        CalendarLocation location = new CalendarLocation(placeName, latitude, longitude);
        calendars.createPlan(new CreateCalendarPlanCommand(
                "h7-existing-" + UUID.randomUUID(),
                "前一場會議",
                CalendarPlacement.interval(end.minus(Duration.ofHours(1)), end, ZoneId.of("Asia/Taipei")),
                "工作",
                null,
                null,
                List.of(),
                List.of(
                        CalendarNodeDraft.at(
                                CalendarTimeNode.absolute(
                                        "start", "前一場開始", end.minus(Duration.ofHours(1))),
                                location),
                        CalendarNodeDraft.at(
                                CalendarTimeNode.absolute("end", "前一場結束", end),
                                location))));
    }

    private static IntentCommand scheduleCommand(
            String title, String start, String end, String place, String source) {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                start,
                end,
                place,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                source);
    }

    private long count(String table, UUID workspace) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspace);
    }

    private void seed(UUID actor, UUID workspace) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'H7 actual entry', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'H7 actual entry', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
    }

    private static IntentCommand contextCommand(String source) {
        return new IntentCommand(
                IntentCommand.Type.ACCEPT_CONTEXT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                source);
    }
}
