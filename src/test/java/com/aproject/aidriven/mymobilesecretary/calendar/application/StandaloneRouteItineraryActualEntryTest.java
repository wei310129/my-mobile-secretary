package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.planner.application.ProviderNeutralRouteService;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelTimeEstimator;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class StandaloneRouteItineraryActualEntryTest extends IntegrationTestBase {

    private static final Instant DEPART = Instant.parse("2026-09-20T01:00:00Z");
    private static final Instant ARRIVE = Instant.parse("2026-09-20T01:40:00Z");

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intents;
    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ProviderNeutralRouteService routes;
    @MockitoBean private TravelTimeEstimator travelTimes;

    @Test
    void nearbyMissingPreviousLocationCreatesVerifiedRouteThenAsksOneQuestion() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    null);
            stubRoute();

            IntentResult result = handle();

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.nextQuestion().code()).isEqualTo("route.previous-location");
            assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
            assertThat(result.responseEnvelope().message())
                    .contains(
                            "已替您安排", "交通方式", "高鐵台中站", "機場捷運",
                            "高鐵桃園站", "往「機場」", "桃園機場",
                            "前一個行程尚未設定地點", "要補上前一個行程的地點嗎")
                    .doesNotContain("共構場站中心定位", "沒有建立自訂地點", "route_preflight");
            assertThat(result.responseEnvelope().message())
                    .doesNotContain("query=0.0%2C0.0");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
        }
    }

    @Test
    void safeRouteKeepsProviderIntervalAndCreatesDepartureReminderExactlyOnce() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubRoute();

            IntentResult created = handle();

            assertThat(created.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(created.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(created.responseEnvelope().message())
                    .contains(
                            "09:00 ~ 09:40",
                            "09:00 📍高鐵站「台中」｜出發",
                            "行程約 40 分鐘",
                            "09:40 📍機場「臺灣桃園國際機場」｜抵達",
                            "要依照行程長度設定出發提醒嗎")
                    .doesNotContain("08:50", "09:55", "出發前緩衝", "行程後緩衝");
            assertThat(jdbc.queryForMap(
                            """
                            SELECT timed_start, timed_end FROM calendar_plan
                            WHERE workspace_id = ? AND title = '台中到桃園機場'
                            """,
                            fixture.workspace()))
                    .containsEntry("timed_start", java.sql.Timestamp.from(DEPART))
                    .containsEntry("timed_end", java.sql.Timestamp.from(ARRIVE));
            assertThat(count("actor_route_operation_preference", fixture.workspace()))
                    .isZero();

            UUID reminderRequest = UUID.randomUUID();
            IntentResult reminded = RequestCorrelationContext.run(
                    reminderRequest,
                    () -> intents.handle("1", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    reminderRequest,
                    () -> intents.handle("1", "TEST"));

            assertThat(replay.message()).isEqualTo(reminded.message());
            assertThat(reminded.message())
                    .contains(
                            "共 2 個提醒時點",
                            "1. 09/20 08:30",
                            "2. 09/20 09:00",
                            "\n\n2. ");
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_reminder_occurrence", fixture.workspace())).isEqualTo(2L);

            for (String followUp : List.of(
                    "哪兩個？",
                    "是哪兩個提醒？",
                    "你剛剛幫我設了哪些提醒？")) {
                IntentResult details = RequestCorrelationContext.run(
                        UUID.randomUUID(), () -> intents.handle(followUp, "TEST"));
                assertThat(details.message()).as(followUp)
                        .contains(
                                "台中到桃園機場",
                                "1. 09/20 08:30",
                                "2. 09/20 09:00")
                        .doesNotContain("完整名稱", "待辦、行程，還是提醒");
            }
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_reminder_occurrence", fixture.workspace())).isEqualTo(2L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "好，前五分鐘叫我",
        "可以，出發前5分鐘提醒我",
        "要，提前 5 分鐘通知我"
    })
    void explicitDepartureLeadOverridesAdaptiveDefaultsExactlyOnce(String answer) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubRoute();
            handle();

            UUID reminderRequest = UUID.randomUUID();
            IntentResult reminded = RequestCorrelationContext.run(
                    reminderRequest, () -> intents.handle(answer, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    reminderRequest, () -> intents.handle(answer, "TEST"));

            assertThat(replay.message()).isEqualTo(reminded.message());
            assertThat(reminded.message())
                    .contains("08:55", "出發前 5 分鐘")
                    .doesNotContain("08:30", "09:00 提醒");
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_reminder_occurrence", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT offset_seconds FROM calendar_reminder_rule "
                                    + "WHERE workspace_id = ?",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(-300L);
        }
    }

    @Test
    void unlinkedMissingLocationFourteenHoursAwayIsIgnored() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    DEPART.minus(Duration.ofHours(15)),
                    DEPART.minus(Duration.ofHours(14)),
                    null);
            stubRoute();

            IntentResult result = handle();

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(result.responseEnvelope().message())
                    .contains("已替您安排", "目前沒有與其他行程衝突")
                    .doesNotContain("尚未設定地點", "銜接仍待確認", "趕得上");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
        }
    }

    @Test
    void provenPreviousConnectionRiskCreatesVerifiedRouteThenAsksOneTypedQuestion() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    new CalendarLocation("既有地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult result = handle();

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.nextQuestion().code()).isEqualTo("route.schedule-conflict");
            assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
            assertThat(result.responseEnvelope().message())
                    .contains(
                            "本次行程",
                            "交通方式",
                            "既有行程",
                            "中間只有 10 分鐘",
                            "兩地移動約需 30 分鐘",
                            "至少不足 20 分鐘")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);

            UUID ignoreRequest = UUID.randomUUID();
            IntentResult kept = RequestCorrelationContext.run(
                    ignoreRequest, () -> intents.handle("忽略這項衝突", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    ignoreRequest, () -> intents.handle("忽略這項衝突", "TEST"));

            assertThat(replay.message()).isEqualTo(kept.message());
            assertThat(kept.nextQuestion().code())
                    .isEqualTo("route.connection-buffer-keep");
            assertThat(kept.message()).contains("前後", "緩衝");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);

            UUID bufferRequest = UUID.randomUUID();
            IntentResult buffered = RequestCorrelationContext.run(
                    bufferRequest, () -> intents.handle("前10分鐘，後15分鐘", "TEST"));
            IntentResult bufferReplay = RequestCorrelationContext.run(
                    bufferRequest, () -> intents.handle("前10分鐘，後15分鐘", "TEST"));

            assertThat(bufferReplay.message()).isEqualTo(buffered.message());
            assertThat(buffered.nextQuestion().code())
                    .isEqualTo("route.departure-reminder");
            assertThat(buffered.message()).contains("已保留本次行程", "銜接風險仍存在");
            assertThat(jdbc.queryForMap(
                            """
                            SELECT general_before_minutes, general_after_minutes
                            FROM actor_route_operation_preference
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .containsEntry("general_before_minutes", 10)
                    .containsEntry("general_after_minutes", 15);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
        }
    }

    @Test
    void choosingSafeLaterTimeReplansProviderAndReschedulesExactlyOnce() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    new CalendarLocation("既有地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            when(routes.plan(any()))
                    .thenReturn(
                            routeResult(DEPART, ARRIVE),
                            routeResult(
                                    DEPART.plus(Duration.ofMinutes(30)),
                                    ARRIVE.plus(Duration.ofMinutes(30))));

            IntentResult risk = handle();
            assertThat(risk.nextQuestion().code()).isEqualTo("route.schedule-conflict");

            UUID adjustmentRequest = UUID.randomUUID();
            IntentResult adjusted = RequestCorrelationContext.run(
                    adjustmentRequest,
                    () -> intents.handle("往後安排到安全時間", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    adjustmentRequest,
                    () -> intents.handle("往後安排到安全時間", "TEST"));

            assertThat(replay.message()).isEqualTo(adjusted.message());
            assertThat(adjusted.nextQuestion().code())
                    .isEqualTo("route.connection-buffer-adjust");

            UUID bufferRequest = UUID.randomUUID();
            adjusted = RequestCorrelationContext.run(
                    bufferRequest, () -> intents.handle("前10分鐘，後15分鐘", "TEST"));
            replay = RequestCorrelationContext.run(
                    bufferRequest, () -> intents.handle("前10分鐘，後15分鐘", "TEST"));

            assertThat(replay.message()).isEqualTo(adjusted.message());
            assertThat(adjusted.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(adjusted.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(adjusted.message())
                    .contains("已把本次行程往後調整到安全時間", "09:30", "10:10")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_plan
                            WHERE workspace_id = ? AND title = '台中到桃園機場'
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace())
                    .toInstant())
                    .isEqualTo(DEPART.plus(Duration.ofMinutes(30)));
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM calendar_route_risk
                            WHERE workspace_id = ? AND status = 'RESOLVED'
                            """,
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(1L);

            ArgumentCaptor<RoutePlanningRequest> requests =
                    ArgumentCaptor.forClass(RoutePlanningRequest.class);
            verify(routes, times(2)).plan(requests.capture());
            assertThat(requests.getAllValues().get(1).time())
                    .isEqualTo(DEPART.plus(Duration.ofMinutes(30)));
        }
    }

    @Test
    void unavailableSafeTimeRevalidationKeepsTheVerifiedRouteUnchanged() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    new CalendarLocation("既有地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            when(routes.plan(any()))
                    .thenReturn(
                            routeResult(DEPART, ARRIVE),
                            RoutePlanningResult.insufficient(List.of(
                                    RoutePlanningResult.ProviderFailure.TDX_FAILED)));

            IntentResult risk = handle();
            assertThat(risk.nextQuestion().code()).isEqualTo("route.schedule-conflict");

            UUID adjustmentRequest = UUID.randomUUID();
            IntentResult unavailable = RequestCorrelationContext.run(
                    adjustmentRequest,
                    () -> intents.handle("往後安排到安全時間", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    adjustmentRequest,
                    () -> intents.handle("往後安排到安全時間", "TEST"));

            assertThat(replay.message()).isEqualTo(unavailable.message());
            assertThat(unavailable.nextQuestion().code())
                    .isEqualTo("route.connection-buffer-adjust");
            unavailable = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("前10分鐘，後15分鐘", "TEST"));
            assertThat(unavailable.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(unavailable.nextQuestion().code()).isEqualTo("route.schedule-conflict");
            assertThat(unavailable.message())
                    .contains("暫時沒有可靠路線資料", "原安排沒有變更")
                    .doesNotContain("TDX_FAILED", "provider", "planId", "nodeId");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_plan
                            WHERE workspace_id = ? AND title = '台中到桃園機場'
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace())
                    .toInstant())
                    .isEqualTo(DEPART);
            assertThat(count("calendar_route_risk", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_route_risk
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("CONFIRMED");
            verify(routes, times(2)).plan(any());
        }
    }

    @Test
    void laterAdjustmentDoesNotClaimSafetyWhenTheRouteHasANextConnectionRisk() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-20T01:50:00Z"),
                    Instant.parse("2026-09-20T02:20:00Z"),
                    new CalendarLocation("下一個活動地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult risk = handle();
            assertThat(risk.nextQuestion().code())
                    .isEqualTo("route.schedule-conflict-keep-only");
            assertThat(risk.message())
                    .contains(
                            "往後安排無法解決",
                            "要忽略衝突並保留原安排嗎");

            IntentResult refused = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("往後安排到安全時間", "TEST"));

            assertThat(refused.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(refused.nextQuestion().code())
                    .isEqualTo("route.schedule-conflict-keep-only");
            assertThat(refused.message())
                    .contains(
                            "往後安排無法解決",
                            "要忽略衝突並保留原安排嗎")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_plan
                            WHERE workspace_id = ? AND title = '台中到桃園機場'
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace())
                    .toInstant())
                    .isEqualTo(DEPART);
            verify(routes, times(1)).plan(any());
        }
    }

    @Test
    void laterAdjustmentDoesNotClaimSafetyWhenBothConnectionsAreAtRisk() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    new CalendarLocation("前一個地點", 25.17, 121.44));
            existingPlan(
                    Instant.parse("2026-09-20T01:50:00Z"),
                    Instant.parse("2026-09-20T02:20:00Z"),
                    new CalendarLocation("下一個地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult risk = handle();
            assertThat(risk.nextQuestion().code())
                    .isEqualTo("route.schedule-conflict-keep-only");

            IntentResult refused = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("往後安排到安全時間", "TEST"));

            assertThat(refused.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(refused.nextQuestion().code())
                    .isEqualTo("route.schedule-conflict-keep-only");
            assertThat(refused.message())
                    .contains(
                            "往後安排無法解決",
                            "要忽略衝突並保留原安排嗎")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_plan
                            WHERE workspace_id = ? AND title = '台中到桃園機場'
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace())
                    .toInstant())
                    .isEqualTo(DEPART);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(3L);
            verify(routes, times(1)).plan(any());
        }
    }

    @Test
    void staleRouteNodeRollsBackTheWholeSafeTimeReschedule() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-19T23:50:00Z"),
                    Instant.parse("2026-09-20T00:50:00Z"),
                    new CalendarLocation("既有地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();
            handle();

            var draft = jdbc.queryForMap(
                    """
                    SELECT id, revision, materialized_plan_id, confirmation_hash
                    FROM calendar_intent_draft
                    WHERE workspace_id = ? AND status = 'MATERIALIZED'
                    """,
                    fixture.workspace());
            UUID draftId = (UUID) draft.get("id");
            long revision = ((Number) draft.get("revision")).longValue();
            UUID planId = (UUID) draft.get("materialized_plan_id");
            String requestKey = "calendar-draft:" + draft.get("confirmation_hash");
            calendars.reviseLockedNode(
                    requestKey, "start", DEPART.plus(Duration.ofMinutes(1)), 1);
            var adjustedOption = routeResult(
                            DEPART.plus(Duration.ofMinutes(20)),
                            ARRIVE.plus(Duration.ofMinutes(20)))
                    .options()
                    .getFirst();

            assertThatThrownBy(() -> drafts.rescheduleMaterializedStandaloneRoute(
                            draftId, revision, adjustedOption))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getCode()).isEqualTo("CALENDAR_ROUTE_CHANGED"));

            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_plan
                            WHERE workspace_id = ? AND id = ?
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace(),
                            planId)
                    .toInstant())
                    .isEqualTo(DEPART);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start FROM calendar_intent_draft
                            WHERE workspace_id = ? AND id = ?
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace(),
                            draftId)
                    .toInstant())
                    .isEqualTo(DEPART);
            assertThat(count("calendar_route_risk", fixture.workspace())).isEqualTo(1L);
        }
    }

    @Test
    void provenNextConnectionRiskCreatesVerifiedRouteBeforeTheChoice() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-20T01:50:00Z"),
                    Instant.parse("2026-09-20T02:20:00Z"),
                    new CalendarLocation("下一個活動地點", 25.17, 121.44));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(30),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult result = handle();

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.nextQuestion().code())
                    .isEqualTo("route.schedule-conflict-keep-only");
            assertThat(result.responseEnvelope().message())
                    .contains(
                            "本次行程",
                            "交通方式",
                            "既有行程",
                            "中間只有 10 分鐘",
                            "兩地移動約需 30 分鐘",
                            "至少不足 20 分鐘",
                            "往後安排無法解決")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
        }
    }

    @Test
    void directTimeOverlapAsksBeforeCreatingTheRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-20T01:20:00Z"),
                    Instant.parse("2026-09-20T02:00:00Z"),
                    new CalendarLocation("目的地附近", 25.08, 121.23));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(5),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult result = handle();

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.nextQuestion().code()).isEqualTo("route.direct-overlap");
            assertThat(result.responseEnvelope().message())
                    .contains(
                            "時間重疊",
                            "🏷️ 既有行程",
                            "1. 調整出發時間",
                            "2. 照原安排保留")
                    .contains("\n\n1. ", "\n\n2. ")
                    .doesNotContain("🏷️ start", "🏷️ end", "planId", "nodeId");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);

            IntentResult notAutoAdjusted = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("往後安排到安全時間", "TEST"));
            assertThat(notAutoAdjusted.action())
                    .isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(notAutoAdjusted.nextQuestion().code())
                    .isEqualTo("route.direct-overlap");
            assertThat(notAutoAdjusted.message())
                    .contains("直接重疊", "不能只靠往後平移判定安全")
                    .doesNotContain("planId", "nodeId", "workspace", "actor");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verify(routes, times(1)).plan(any());

            UUID forceRequest = UUID.randomUUID();
            IntentResult forced = RequestCorrelationContext.run(
                    forceRequest, () -> intents.handle("2", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    forceRequest, () -> intents.handle("2", "TEST"));

            assertThat(replay.message()).isEqualTo(forced.message());
            assertThat(forced.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(forced.message()).contains("已依您的確認建立", "仍與既有行程時間重疊");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"取消", "取消這次的行程建立", "這次不要了"})
    void cancelCurrentDirectOverlapDraftKeepsTheExistingPlan(String cancelPhrase) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            existingPlan(
                    Instant.parse("2026-09-20T01:20:00Z"),
                    Instant.parse("2026-09-20T02:00:00Z"),
                    new CalendarLocation("目的地附近", 25.08, 121.23));
            when(travelTimes.estimateEvidence(
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Instant.class)))
                    .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                            Duration.ofMinutes(5),
                            TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
            stubRoute();

            IntentResult proposed = handle();
            assertThat(proposed.nextQuestion().code()).isEqualTo("route.direct-overlap");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);

            UUID cancelRequest = UUID.randomUUID();
            IntentResult canceled = RequestCorrelationContext.run(
                    cancelRequest, () -> intents.handle(cancelPhrase, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    cancelRequest, () -> intents.handle(cancelPhrase, "TEST"));

            assertThat(replay.message()).isEqualTo(canceled.message());
            assertThat(canceled.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(canceled.message())
                    .contains("已取消未完成的路線規劃", "台中到桃園機場", "已建立的行程沒有變更")
                    .doesNotContain("workflow", "draft", "UUID");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND title = '台中到桃園機場'
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("DISCARDED");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND question_code = 'route.direct-overlap'
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("CANCELED");
            verify(routes, times(1)).plan(any());
        }
    }

    @Test
    void acceptingProviderUnavailableQuestionRetainsDraftWithoutCreatingPlan() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            when(routes.plan(any())).thenReturn(RoutePlanningResult.insufficient(List.of(
                    RoutePlanningResult.ProviderFailure.TDX_FAILED,
                    RoutePlanningResult.ProviderFailure.GOOGLE_FAILED)));
            IntentResult unavailable = handle();
            assertThat(unavailable.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.ACCEPT_CONTEXT,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    IntentOptions.empty(),
                    "好，先保留"));
            IntentResult retained = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("好，先保留", "TEST"));
            assertThat(retained.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(retained.responseEnvelope().message())
                    .contains("已保留", "尚未建立")
                    .doesNotContain("TDX_FAILED", "GOOGLE_FAILED", "provider");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(jdbc.queryForMap(
                            """
                            SELECT route_provider_status, transport_mode, route_time_role,
                                   route_provider, route_evidence_retrieved_at
                            FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            fixture.workspace(), fixture.context().actorId()))
                    .containsEntry("route_provider_status", "RETAINED")
                    .containsEntry("transport_mode", "TRANSIT")
                    .containsEntry("route_time_role", "DEPART_AT")
                    .containsEntry("route_provider", null)
                    .containsEntry("route_evidence_retrieved_at", null);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND question_code = 'route.provider-unavailable'
                              AND status = 'ANSWERED'
                            """,
                            Long.class,
                            fixture.workspace(), fixture.context().actorId()))
                    .isEqualTo(1L);

            stubRoute();
            IntentResult retried = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("再幫我查一次路線", "TEST"));

            assertThat(retried.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(retried.responseEnvelope().message())
                    .contains("已替您安排", "交通方式")
                    .doesNotContain("起點", "目的地", "哪種交通方式", "provider");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock = """
                    繼續|新的|全部清除重來
                    繼續處理|新操作|清除重來
                    延續|開始新的操作|全部取消重來
                    """)
    void knownFiveStepLifecyclePathCanResumeSwitchResumeAndClearThroughActualEntry(
            String resumePhrase, String newOperationPhrase, String clearPhrase) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            when(routes.plan(any())).thenReturn(RoutePlanningResult.insufficient(List.of(
                    RoutePlanningResult.ProviderFailure.TDX_FAILED,
                    RoutePlanningResult.ProviderFailure.GOOGLE_FAILED)));
            IntentResult first = handle();
            assertThat(first.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            verify(routes, times(1)).plan(any());

            IntentResult firstResume = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(resumePhrase, "TEST"));
            assertThat(firstResume.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(firstResume.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(firstResume.message())
                    .contains("目前正在處理", "台中到桃園機場", "要保留這份待確認安排");
            assertResumeContextOrder(firstResume.message(), "台中到桃園機場");
            assertThat(firstResume.message().indexOf("要保留這份待確認安排"))
                    .isEqualTo(firstResume.message().lastIndexOf("要保留這份待確認安排"));

            String newText = "幫我規劃9月20日上午十點從捷運大坪林站到捷運新店站的行程";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "大坪林到新店站",
                    null,
                    "2026-09-20T10:00:00+08:00",
                    null,
                    "捷運新店站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin("捷運大坪林站", null),
                    newText));
            IntentResult contextChoice = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(newText, "TEST"));
            assertThat(contextChoice.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(contextChoice.nextQuestion().code()).isEqualTo("conversation.context-target");
            assertThat(contextChoice.message())
                    .contains(
                            "繼續目前操作",
                            "保留目前進度並開始新的操作",
                            "台中到桃園機場");
            verify(routes, times(1)).plan(any());
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM conversation_pending_question WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING' AND deferred_workflow_id IS NOT NULL",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);

            IntentResult startedNew = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(newOperationPhrase, "TEST"));
            assertThat(startedNew.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(startedNew.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(startedNew.message()).contains("大坪林到新店站", "要保留這份待確認安排");
            verify(routes, times(2)).plan(any());

            IntentResult resumedNew = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(resumePhrase, "TEST"));
            assertThat(resumedNew.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(resumedNew.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(resumedNew.message())
                    .contains("目前正在處理", "大坪林到新店站", "要保留這份待確認安排");
            assertResumeContextOrder(resumedNew.message(), "大坪林到新店站");
            assertThat(resumedNew.message().indexOf("要保留這份待確認安排"))
                    .isEqualTo(resumedNew.message().lastIndexOf("要保留這份待確認安排"));
            verify(routes, times(2)).plan(any());

            IntentResult cleared = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(clearPhrase, "TEST"));
            assertThat(cleared.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(cleared.message())
                    .contains("已清除未完成的路線規劃", "大坪林到新店站", "已建立的行程沒有變更");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_intent_draft WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(routes, times(2)).plan(any());
        }
    }

    private static void assertResumeContextOrder(String message, String topic) {
        int topicHeader = message.indexOf("目前正在處理：");
        int topicValue = message.indexOf(topic);
        int progressHeader = message.indexOf("目前進度：");
        int nextHeader = message.indexOf("下一步：");
        assertThat(topicHeader).isGreaterThanOrEqualTo(0);
        assertThat(topicValue).isGreaterThan(topicHeader);
        assertThat(progressHeader).isGreaterThan(topicValue);
        assertThat(nextHeader).isGreaterThan(progressHeader);
    }

    @Test
    void legacyRouteContextCanResumeThenClearAndRestartWithoutDeletingCommittedPlans() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            when(routes.plan(any())).thenReturn(RoutePlanningResult.insufficient(List.of(
                    RoutePlanningResult.ProviderFailure.TDX_FAILED,
                    RoutePlanningResult.ProviderFailure.GOOGLE_FAILED)));
            IntentResult unavailable = handle();
            assertThat(unavailable.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            UUID draftId = jdbc.queryForObject(
                    "SELECT id FROM calendar_intent_draft WHERE workspace_id = ? AND created_by_user_id = ?",
                    UUID.class,
                    fixture.workspace(),
                    fixture.context().actorId());
            jdbc.update(
                    """
                    UPDATE conversation_pending_question
                    SET root_domain = 'task', workflow_safe_label = NULL,
                        question_code = 'conversation.context-target',
                        interrupted_question_code = 'route.provider-unavailable'
                    WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                    """,
                    fixture.workspace(),
                    fixture.context().actorId());

            Fixture otherActor = fixture();
            try (WorkspaceContextHolder.Scope other =
                    WorkspaceContextHolder.open(otherActor.context())) {
                IntentResult isolated = RequestCorrelationContext.run(
                        UUID.randomUUID(), () -> intents.handle("全部清除重來", "TEST"));
                assertThat(isolated.message()).contains("目前沒有可安全清除的未完成路線規劃");
            }
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE id = ?",
                            String.class,
                            draftId))
                    .isEqualTo("PENDING");

            IntentResult newOperation = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("新的", "TEST"));
            assertThat(newOperation.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(newOperation.nextQuestion().code())
                    .isEqualTo("conversation.new-operation-content");
            assertThat(newOperation.message()).contains("台中到桃園機場", "請告訴我要開始的新操作");

            IntentResult resumed = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("繼續", "TEST"));

            assertThat(resumed.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(resumed.message())
                    .contains(
                            "目前正在處理：",
                            "台中到桃園機場",
                            "目前進度：",
                            "下一步：",
                            "要保留這份待確認安排");
            assertThat(resumed.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(jdbc.queryForObject(
                            "SELECT question_code FROM conversation_pending_question WHERE workflow_id = ?",
                            String.class,
                            draftId))
                    .isEqualTo("route.provider-unavailable");

            jdbc.update(
                    """
                    UPDATE conversation_pending_question
                    SET question_code = 'intent.unknown-action', interrupted_question_code = NULL
                    WHERE workflow_id = ? AND status = 'PENDING'
                    """,
                    draftId);
            IntentResult recovered = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("繼續", "TEST"));
            assertThat(recovered.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(recovered.message())
                    .contains(
                            "目前正在處理：",
                            "台中到桃園機場",
                            "目前進度：",
                            "下一步：",
                            "要保留這份待確認安排");
            assertThat(recovered.nextQuestion().code()).isEqualTo("route.provider-unavailable");

            UUID clearRequest = UUID.randomUUID();
            IntentResult cleared = RequestCorrelationContext.run(
                    clearRequest, () -> intents.handle("全部清除重來", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    clearRequest, () -> intents.handle("全部清除重來", "TEST"));

            assertThat(replay.message()).isEqualTo(cleared.message());
            assertThat(cleared.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(cleared.message())
                    .contains("已清除未完成的路線規劃", "台中到桃園機場", "已建立的行程沒有變更")
                    .doesNotContain("workflow", "draft", "UUID");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE id = ?",
                            String.class,
                            draftId))
                    .isEqualTo("DISCARDED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM conversation_pending_question WHERE workflow_id = ?",
                            String.class,
                            draftId))
                    .isEqualTo("CANCELED");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void orphanLegacyRoutePendingRecoversThenAcceptsACompleteNewRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            when(routes.plan(any())).thenReturn(RoutePlanningResult.insufficient(List.of(
                    RoutePlanningResult.ProviderFailure.TDX_FAILED,
                    RoutePlanningResult.ProviderFailure.GOOGLE_FAILED)));
            IntentResult unavailable = handle();
            assertThat(unavailable.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            jdbc.update(
                    """
                    UPDATE conversation_pending_question
                    SET root_domain = 'task', workflow_id = ?, workflow_safe_label = '倒垃圾',
                        question_code = 'route.activity-duration'
                    WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                    """,
                    UUID.randomUUID(), fixture.workspace(), fixture.context().actorId());

            IntentResult recovered = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("繼續", "TEST"));
            assertThat(recovered.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(recovered.message())
                    .contains("先前的路線規劃已失效", "行事曆沒有新增資料", "起點、目的地與時間")
                    .doesNotContain("倒垃圾", "NotFound", "workflow");
            assertThat(recovered.nextQuestion().code()).isEqualTo("route.new-request");
            assertThat(jdbc.queryForMap(
                            """
                            SELECT root_domain, question_code, focus_id IS NULL AS detached
                            FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND status = 'PENDING'
                            """,
                            fixture.workspace(), fixture.context().actorId()))
                    .containsEntry("root_domain", "route")
                    .containsEntry("question_code", "route.new-request")
                    .containsEntry("detached", true);

            IntentResult restarted = handle();
            assertThat(restarted.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(restarted.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(restarted.message())
                    .contains("要保留這份待確認安排")
                    .doesNotContain("活動預計多久", "幾點結束", "倒垃圾");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND title = '台中到桃園機場' AND status = 'PENDING'
                            """,
                            Long.class, fixture.workspace(), fixture.context().actorId()))
                    .isEqualTo(1L);
            verify(routes, times(2)).plan(any());
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    private IntentResult handle() {
        String text = "幫我安排9月20日上午九點從高鐵台中站到桃園機場的行程";
        interpreter.nextCommand(new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                "台中到桃園機場",
                null,
                "2026-09-20T09:00:00+08:00",
                null,
                "桃園機場",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty().withDepartureOrigin("高鐵台中站", null),
                text));
        return RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(text, "TEST"));
    }

    private void stubRoute() {
        when(routes.plan(any())).thenReturn(routeResult(DEPART, ARRIVE));
    }

    private static RoutePlanningResult routeResult(Instant depart, Instant arrive) {
        var option = new RoutePlanningResult.RouteOption(
                RoutePlanningResult.Provider.TDX,
                RoutePlanningRequest.TravelMode.TRANSIT,
                depart,
                arrive,
                Duration.ofMinutes(40),
                Duration.ZERO,
                null,
                false,
                true,
                Instant.parse("2026-09-19T12:00:00Z"),
                List.of(
                        new RoutePlanningResult.TransitLeg(
                                "HIGH_SPEED_RAIL", "台灣高鐵", "北上",
                                "高鐵台中站", "高鐵桃園站"),
                        new RoutePlanningResult.TransitLeg(
                                "MRT", "機場捷運", "機場",
                                "高鐵桃園站", "桃園機場")));
        return RoutePlanningResult.available(List.of(option), List.of());
    }

    private void existingPlan(Instant start, Instant end, CalendarLocation location) {
        CalendarTimeNode startNode = CalendarTimeNode.absolute("start", "既有開始", start);
        CalendarTimeNode endNode = CalendarTimeNode.absolute("end", "既有結束", end);
        calendars.createPlan(new CreateCalendarPlanCommand(
                "standalone-existing-" + UUID.randomUUID(),
                "既有行程",
                CalendarPlacement.interval(start, end, ZoneId.of("Asia/Taipei")),
                null,
                null,
                null,
                List.of(),
                location == null
                        ? List.of(CalendarNodeDraft.of(startNode), CalendarNodeDraft.of(endNode))
                        : List.of(
                                CalendarNodeDraft.at(startNode, location),
                                CalendarNodeDraft.at(endNode, location))));
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Standalone route test', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Standalone route test', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
        return new Fixture(
                workspace,
                new WorkspaceContext(
                        actor,
                        workspace,
                        WorkspaceChannel.TEST,
                        "standalone-route",
                        "conversation"));
    }

    private long count(String table, UUID workspace) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspace);
    }

    private record Fixture(UUID workspace, WorkspaceContext context) {}
}
