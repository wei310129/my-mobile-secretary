package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarIntentDraftActualEntryPathTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intents;
    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private ConversationFocusService focuses;
    @Autowired private ConversationFocusQuoteResolver quotes;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean
    private com.aproject.aidriven.mymobilesecretary.planner.application
                    .ProviderNeutralRouteService
            routePlanner;
    @MockitoBean private GooglePlacesClient googlePlacesClient;

    @Test
    void actualEntryReplaysProposalAndMaterializesOnlyAfterConfirmation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "9月10日下午三點到四點客戶會議，只留草稿，不要直接建立";
            IntentCommand proposal = create("客戶會議", text);
            UUID request = UUID.randomUUID();
            interpreter.nextCommand(proposal);

            IntentResult first = RequestCorrelationContext.run(
                    request, () -> intents.handle(text, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    request, () -> intents.handle(text, "TEST"));

            assertThat(replay.message()).isEqualTo(first.message());
            assertThat(first.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            assertThat(first.responseEnvelope().message())
                    .contains("尚未放進行事曆", "要照這個版本建立嗎")
                    .doesNotContain("Intent", "reason=", "revision:", "nodeId");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(focuses.activeFocus()).hasValueSatisfying(focus -> {
                assertThat(focus.getRootDomain())
                        .isEqualTo(CalendarIntentDraftConversationService.FOCUS_DOMAIN);
                assertThat(focus.getActivityCode()).isEqualTo("revision:1");
            });

            String confirmation = "好，就照這個版本建立";
            interpreter.nextCommand(context(IntentCommand.Type.ACCEPT_CONTEXT, confirmation));
            IntentResult confirmed = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(confirmation, "TEST"));

            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(confirmed.message()).contains("已把", "放進行事曆");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("MATERIALIZED");
            assertThat(focuses.activeFocus()).isEmpty();
        }
    }

    @Test
    void quotedParallelDraftWinsCorrectionAndCanConfirmAfterRename() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            IntentResult firstResult = handle(
                    "9月10日下午三點到四點會議，先留草稿",
                    create("會議", "9月10日下午三點到四點會議，先留草稿"));
            assertThat(firstResult.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            UUID firstDraft = focuses.activeFocus().orElseThrow().getWorkflowId();
            UUID firstFocus = focuses.activeFocus().orElseThrow().getId();

            handle(
                    "9月11日晚上六點到七點聚餐，只留草稿",
                    new IntentCommand(
                            IntentCommand.Type.CREATE_SCHEDULE,
                            "聚餐",
                            null,
                            "2026-09-11T18:00:00+08:00",
                            "2026-09-11T19:00:00+08:00",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            IntentOptions.empty(),
                            "9月11日晚上六點到七點聚餐，只留草稿"));
            UUID secondDraft = focuses.activeFocus().orElseThrow().getWorkflowId();
            assertThat(secondDraft).isNotEqualTo(firstDraft);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();

            var resolution = quotes.resolveSuspendedFocus(firstFocus);
            assertThat(resolution.control().focusId()).isEqualTo(firstFocus);
            focuses.resume(resolution.control().focusId(), "d".repeat(64));

            String correctionText = "改叫季度會議，並改到下午四點";
            IntentCommand correction = new IntentCommand(
                    IntentCommand.Type.RESCHEDULE_SCHEDULE,
                    null,
                    null,
                    "2026-09-10T16:00:00+08:00",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withNewTitle("季度會議"),
                    correctionText);
            IntentResult revised = handle(correctionText, correction);

            assertThat(revised.message()).contains("季度會議", "還沒有放進行事曆");
            var first = drafts.get(firstDraft);
            var second = drafts.get(secondDraft);
            assertThat(first.title()).isEqualTo("季度會議");
            assertThat(first.revision()).isEqualTo(2L);
            assertThat(((CalendarPlacement.TimedInterval) first.placement()).start())
                    .isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
            assertThat(second.title()).isEqualTo("聚餐");
            assertThat(second.revision()).isEqualTo(1L);

            IntentResult confirmed = handle(
                    "確認這個新版",
                    context(IntentCommand.Type.ACCEPT_CONTEXT, "確認這個新版"));
            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(drafts.get(firstDraft).status()).isEqualTo(Status.MATERIALIZED);
            assertThat(drafts.get(secondDraft).status()).isEqualTo(Status.PENDING);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        }
    }

    @Test
    void exactSystemPlaceIsMaterializedWithoutCreatingAUserPlace() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            long beforePlaces = count("place", fixture.workspace());
            String text = "9月10日下午三點到四點安排在高鐵台中站搭車";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.CREATE_SCHEDULE,
                    "搭高鐵",
                    null,
                    "2026-09-10T15:00:00+08:00",
                    "2026-09-10T16:00:00+08:00",
                    "高鐵台中站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    IntentOptions.empty(),
                    text);

            IntentResult result = handle(text, command);

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(result.message())
                    .contains("已建立行程")
                    .doesNotContain("系統公共地點", "沒有建立自訂地點");
            assertThat(count("place", fixture.workspace())).isEqualTo(beforePlaces);
            assertThat(jdbc.queryForList("""
                    SELECT node.location_label
                    FROM calendar_time_node node
                    JOIN calendar_plan plan ON plan.id = node.plan_id
                    WHERE plan.workspace_id = ? AND plan.title = '搭高鐵'
                    """, String.class, fixture.workspace()))
                    .hasSize(2)
                    .containsOnly("高鐵站「台中」");
        }
    }

    @Test
    void crossRegionSystemPlaceKeepsCalendarSlotsAndResumesAfterOneCountyAnswer() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            long beforePlaces = count("place", fixture.workspace());
            String text = "9月10日下午三點到四點安排在市政府站開會";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.CREATE_SCHEDULE,
                    "市府會議",
                    null,
                    "2026-09-10T15:00:00+08:00",
                    "2026-09-10T16:00:00+08:00",
                    "市政府站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    IntentOptions.empty(),
                    text);

            IntentResult ambiguous = handle(text, command);

            assertThat(ambiguous.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(ambiguous.message()).contains("其他已確認資訊已保留", "哪個縣市");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1);
            assertThat(count("public_place_lookup_draft", fixture.workspace())).isEqualTo(1);

            IntentResult resolved = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("台北市", "TEST"));

            assertThat(resolved.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(resolved.message())
                    .contains("市府會議", "系統公共地點", "沒有建立自訂地點");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(count("place", fixture.workspace())).isEqualTo(beforePlaces);
        }
    }

    @Test
    void routeActivityMaterializesOnceAndPersistsOptionalTransportConversation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "9月10日上午九點從高鐵台中站到桃園機場參加會議，幫我安排行程";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "參加會議", null,
                    "2026-09-10T09:00:00+08:00",
                    "2026-09-10T10:00:00+08:00",
                    "桃園機場", null, null, null, null, null, null, false,
                    IntentOptions.empty().withDepartureOrigin("高鐵台中站", null), text);

            IntentResult created = handle(text, command);

            assertThat(created.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(created.nextQuestion().code()).isEqualTo("route.transport-offer");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT transport_offer_status FROM calendar_intent_draft WHERE workspace_id = ?",
                    String.class, fixture.workspace())).isEqualTo("OFFERED");
            assertThat(jdbc.queryForObject("""
                    SELECT q.workflow_id = d.id
                    FROM conversation_pending_question q
                    JOIN calendar_intent_draft d
                      ON d.workspace_id = q.workspace_id
                     AND d.created_by_user_id = q.created_by_user_id
                    WHERE q.workspace_id = ? AND q.created_by_user_id = ?
                      AND q.status = 'PENDING' AND q.question_code = 'route.transport-offer'
                    """, Boolean.class, fixture.workspace(), fixture.context().actorId())).isTrue();

            IntentResult accepted = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("好", "TEST"));
            assertThat(accepted.nextQuestion().code()).isEqualTo("route.activity-adjustability");

            IntentResult locked = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("活動時間固定，由主辦單位決定", "TEST"));
            assertThat(locked.nextQuestion().code()).isEqualTo("route.transport-mode");
            assertThat(locked.message()).contains("交通變動不得修改活動");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(jdbc.queryForMap(
                    "SELECT transport_offer_status, activity_adjustability FROM calendar_intent_draft WHERE workspace_id = ?",
                    fixture.workspace()))
                    .containsEntry("transport_offer_status", "ACCEPTED")
                    .containsEntry("activity_adjustability", "LOCKED");
        }
    }

    @Test
    void activityWithoutOriginUsesSameDurableHomeQuestionBeforeTransportOffer() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            jdbc.update(
                    """
                    INSERT INTO place (
                        name, address, latitude, longitude, type, created_at,
                        workspace_id, created_by_user_id)
                    VALUES ('我家', NULL, 24.9824, 121.5415, 'HOME', CURRENT_TIMESTAMP, ?, ?)
                    """,
                    fixture.workspace(),
                    fixture.context().actorId());
            String text = "明天上午九點到桃園機場參加會議，幫我安排行程";
            IntentResult missing = handle(
                    text,
                    new IntentCommand(
                            IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                            "參加會議",
                            null,
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "桃園機場",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            IntentOptions.empty(),
                            text));

            assertThat(missing.nextQuestion().code()).isEqualTo("route.home-location");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(routePlanner);

            IntentResult created = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("我家", "TEST"));

            assertThat(created.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(created.message())
                    .startsWith("這次先以家裡為出發地。")
                    .contains(
                            "已建立行程",
                            "是否要為這個活動規劃交通",
                            "1. 規劃交通方式",
                            "2. 不規劃交通");
            assertThat(created.nextQuestion().code()).isEqualTo("route.transport-offer");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(count("actor_location_preference", fixture.workspace())).isEqualTo(1);
            assertThat(jdbc.queryForMap(
                            """
                            SELECT status, transport_offer_status, route_provider_status,
                                   route_journey_kind
                            FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            fixture.workspace()))
                    .containsEntry("status", "MATERIALIZED")
                    .containsEntry("transport_offer_status", "OFFERED")
                    .containsEntry("route_provider_status", "NOT_REQUESTED")
                    .containsEntry("route_journey_kind", "ACTIVITY_WITH_TRANSPORT");
            verifyNoInteractions(routePlanner);
        }
    }

    @Test
    void standalonePlanningSynonymUsesExactPublicPointAndMaterializesProviderRouteOnce() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "大坪林到台北車站",
                    null,
                    "2026-08-05T09:00:00+08:00",
                    "2026-08-05T10:00:00+08:00",
                    "捷運台北車站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin("大坪林", null),
                    text));
            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.TDX,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.TRANSIT,
                            Instant.parse("2026-08-05T01:00:00Z"),
                            Instant.parse("2026-08-05T01:40:00Z"),
                            Duration.ofMinutes(40),
                            Duration.ZERO,
                            null,
                            false,
                            true,
                            Instant.parse("2026-08-04T04:00:00Z"));
            when(routePlanner.plan(any())).thenReturn(
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.available(List.of(option), List.of()));
            UUID requestId = UUID.randomUUID();

            IntentResult first = RequestCorrelationContext.run(
                    requestId, () -> intents.handle(text, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    requestId, () -> intents.handle(text, "TEST"));

            assertThat(first.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(first.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(replay.message()).isEqualTo(first.message());
            assertThat(first.responseEnvelope().message())
                    .contains(
                            "已替您安排", "09:00 ~ 09:40", "40 分鐘", "TDX", "桃園捷運",
                            "要依照行程長度設定出發提醒嗎")
                    .doesNotContain(
                            "08:50", "09:55", "出發前緩衝開始", "行程後緩衝結束",
                            "可靠路線資料", "共構場站中心定位", "沒有建立自訂地點",
                            "PLAN_ROUTE_ITINERARY", "workspace", "actor");
            assertThat(first.message().split(
                                    "https://www.google.com/maps/search/\\?api=1&query=", -1)
                            .length
                    - 1)
                    .isEqualTo(2);
            assertThat(first.message())
                    .contains(
                            "🗺️ Google Maps 路線規劃",
                            "https://www.google.com/maps/dir/?api=1&origin=24.98272%2C121.54134"
                                    + "&destination=25.04869%2C121.51428&travelmode=transit");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_time_node", fixture.workspace())).isEqualTo(2L);
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("actor_route_operation_preference", fixture.workspace()))
                    .isZero();
            assertThat(jdbc.queryForMap(
                            """
                            SELECT status, placement_kind, transport_mode, route_journey_kind
                            FROM calendar_intent_draft WHERE workspace_id = ?
                            """,
                            fixture.workspace()))
                    .containsEntry("status", "MATERIALIZED")
                    .containsEntry("placement_kind", "TIMED_INTERVAL")
                    .containsEntry("transport_mode", "TRANSIT")
                    .containsEntry("route_journey_kind", "STANDALONE_TRIP");
            assertThat(jdbc.queryForMap(
                            """
                            SELECT timed_start, timed_end
                            FROM calendar_intent_draft WHERE workspace_id = ?
                            """,
                            fixture.workspace()))
                    .containsEntry(
                            "timed_start",
                            java.sql.Timestamp.from(Instant.parse("2026-08-05T01:00:00Z")))
                    .containsEntry(
                            "timed_end",
                            java.sql.Timestamp.from(Instant.parse("2026-08-05T01:40:00Z")));
            assertThat(jdbc.queryForList(
                            """
                            SELECT node_key, location_label FROM calendar_time_node
                            WHERE workspace_id = ? ORDER BY resolved_time
                            """,
                            fixture.workspace()))
                    .extracting(row -> row.get("node_key"))
                    .containsExactly("start", "end");
            var captor = org.mockito.ArgumentCaptor.forClass(
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningRequest.class);
            verify(routePlanner).plan(captor.capture());
            assertThat(captor.getValue().mode())
                    .isEqualTo(com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningRequest.TravelMode.TRANSIT);
            assertThat(captor.getValue().timeRole())
                    .isEqualTo(com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningRequest.TimeRole.DEPART_AT);
        }
    }

    @Test
    void pendingRouteBufferAnswerUsesBoundWorkflowWithTaskFocusAndParallelDrafts() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubDefaultTransitRoute();
            String firstText = "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            IntentResult first = handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));
            assertThat(first.nextQuestion().code()).isEqualTo("route.departure-reminder");

            if (focuses.hasActiveFocus()) {
                focuses.switchResource(
                        "TASK", "task:route-context-fixture", "整理行李", "a".repeat(64));
            } else {
                focuses.enterResource(
                        "TASK", "task:route-context-fixture", "整理行李", "a".repeat(64));
            }

            String secondText = "另外幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            IntentResult second = handle(
                    secondText,
                    standaloneRouteCommand(
                            "大坪林到新店站",
                            "2026-08-05T16:00:00+08:00",
                            "2026-08-05T17:00:00+08:00",
                            "捷運新店站",
                            "捷運大坪林站",
                            secondText));
            assertThat(second.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(second.responseEnvelope().message())
                    .contains(
                            "原本的「整理行李」先保留",
                            "現在改處理「大坪林到新店站」");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(focuses.activeFocus()).hasValueSatisfying(
                    focus -> assertThat(focus.getRootDomain()).isEqualTo("CALENDAR_DRAFT"));

            UUID pendingWorkflow = jdbc.queryForObject(
                    """
                    SELECT workflow_id FROM conversation_pending_question
                    WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                    """,
                    UUID.class,
                    fixture.workspace(),
                    fixture.context().actorId());
            assertThat(jdbc.queryForObject(
                            """
                            SELECT root_domain FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("calendar_draft");

            IntentResult answered = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("這次不用出發提醒", "TEST"));

            assertThat(answered.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(answered.message()).contains("這次不另外設定出發提醒");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE id = ?",
                            String.class,
                            pendingWorkflow))
                    .isEqualTo("MATERIALIZED");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                            """,
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isZero();
            verify(routePlanner, org.mockito.Mockito.times(2)).plan(any());
        }
    }

    @Test
    void ambiguousNewOperationStagesExactlyOneTypedDraftBeforeProviderExecution() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubUnavailableThenDefaultTransitRoute();
            String firstText = "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            IntentResult first = handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));
            assertThat(first.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);

            String ambiguous = "幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            IntentResult choice = handle(
                    ambiguous,
                    standaloneRouteCommand(
                            "大坪林到新店站",
                            "2026-08-05T16:00:00+08:00",
                            "2026-08-05T17:00:00+08:00",
                            "捷運新店站",
                            "捷運大坪林站",
                            ambiguous));

            assertThat(choice.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(choice.nextQuestion().code()).isEqualTo("conversation.context-target");
            assertThat(choice.responseEnvelope().message())
                    .contains("大坪林到台北車站", "繼續", "開始新的操作")
                    .doesNotContain("workspace", "actor", "UUID");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM conversation_pending_question WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING' AND deferred_workflow_id IS NOT NULL",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_intent_draft WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING' AND route_provider_status = 'NOT_REQUESTED'",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);
            verify(routePlanner).plan(any());
        }
    }

    @Test
    void contextChoiceCanResumeTheInterruptedRouteQuestionAndDiscardTheStagedRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubUnavailableThenDefaultTransitRoute();
            String firstText =
                    "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));

            String ambiguous = "幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            IntentResult choice = handle(
                    ambiguous,
                    standaloneRouteCommand(
                            "大坪林到新店站",
                            "2026-08-05T16:00:00+08:00",
                            "2026-08-05T17:00:00+08:00",
                            "捷運新店站",
                            "捷運大坪林站",
                            ambiguous));
            assertThat(choice.nextQuestion().code()).isEqualTo("conversation.context-target");

            IntentResult resumed = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("繼續原本的操作", "TEST"));

            assertThat(resumed.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(resumed.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(resumed.responseEnvelope().message())
                    .contains(
                            "目前正在處理：",
                            "大坪林到台北車站",
                            "目前進度：",
                            "下一步：",
                            "路線資料");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT question_code FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("route.provider-unavailable");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_intent_draft WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_intent_draft WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'DISCARDED'",
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(routePlanner).plan(any());
        }
    }

    @Test
    void completedRouteReminderDoesNotBlockTheNextCompleteRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubDefaultTransitRoute();
            String firstText =
                    "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            IntentResult first = handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));
            assertThat(first.nextQuestion().code()).isEqualTo("route.departure-reminder");

            String nextText = "幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            IntentResult next = handle(
                    nextText,
                    standaloneRouteCommand(
                            "大坪林到新店站",
                            "2026-08-05T16:00:00+08:00",
                            "2026-08-05T17:00:00+08:00",
                            "捷運新店站",
                            "捷運大坪林站",
                            nextText));

            assertThat(next.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(next.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(next.message())
                    .contains("好的，已替您安排", "台北捷運「捷運新店」")
                    .doesNotContain("尚未完成", "繼續這個操作", "開始新的操作");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isZero();
            verify(routePlanner, org.mockito.Mockito.times(2)).plan(any());
        }
    }

    @Test
    void contextChoiceCanRetainOldRouteAndStartExactlyOneNewOperation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubUnavailableThenDefaultTransitRoute();
            String firstText =
                    "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));

            String ambiguous = "幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            handle(
                    ambiguous,
                    standaloneRouteCommand(
                            "大坪林到新店站",
                            "2026-08-05T16:00:00+08:00",
                            "2026-08-05T17:00:00+08:00",
                            "捷運新店站",
                            "捷運大坪林站",
                            ambiguous));

            IntentResult created = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("開始新的操作", "TEST"));
            assertThat(created.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(created.responseEnvelope().message())
                    .contains("原本的「大坪林到台北車站」先保留", "大坪林到新店站");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verify(routePlanner, org.mockito.Mockito.times(2)).plan(any());
        }
    }

    @Test
    void ambiguousContextChoiceReplayDoesNotAdvanceRevisionOrCreateAnotherDraft() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubUnavailableThenDefaultTransitRoute();
            String firstText =
                    "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
            handle(
                    firstText,
                    standaloneRouteCommand(
                            "大坪林到台北車站",
                            "2026-08-05T09:00:00+08:00",
                            "2026-08-05T10:00:00+08:00",
                            "捷運台北車站",
                            "大坪林",
                            firstText));

            String ambiguous = "幫我規劃明天下午四點從捷運大坪林到捷運新店站";
            interpreter.nextCommand(standaloneRouteCommand(
                    "大坪林到新店站",
                    "2026-08-05T16:00:00+08:00",
                    "2026-08-05T17:00:00+08:00",
                    "捷運新店站",
                    "捷運大坪林站",
                    ambiguous));
            UUID inbound = UUID.randomUUID();
            IntentResult firstChoice = RequestCorrelationContext.run(
                    inbound, () -> intents.handle(ambiguous, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    inbound, () -> intents.handle(ambiguous, "TEST"));

            assertThat(replay.message()).isEqualTo(firstChoice.message());
            assertThat(replay.nextQuestion().code()).isEqualTo("conversation.context-target");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT revision FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                            """,
                            Long.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(2L);
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(routePlanner).plan(any());
        }
    }

    @Test
    void missingOriginPersistsTypedHomeQuestionThenSavesConfirmedPlaceAndResumesRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            Long homeId = jdbc.queryForObject(
                    """
                    INSERT INTO place (
                        name, address, latitude, longitude, type, created_at,
                        workspace_id, created_by_user_id)
                    VALUES ('我家', NULL, 24.9824, 121.5415, 'HOME', CURRENT_TIMESTAMP, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    fixture.workspace(),
                    fixture.context().actorId());
            String text = "幫我規劃明天早上九點到桃園捷運「捷運台北車站」的行程";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "到台北車站",
                    null,
                    "2026-08-05T09:00:00+08:00",
                    "2026-08-05T10:00:00+08:00",
                    "捷運台北車站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty(),
                    text);

            IntentResult missing = handle(text, command);

            assertThat(missing.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(missing.nextQuestion().code()).isEqualTo("route.home-location");
            assertThat(missing.message())
                    .isEqualTo("我還沒有您的住家地點。要把哪個地點設為家裡？");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("actor_location_preference", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            """
                            SELECT q.workflow_id = d.id
                            FROM conversation_pending_question q
                            JOIN calendar_intent_draft d
                              ON d.id = q.workflow_id
                             AND d.workspace_id = q.workspace_id
                             AND d.created_by_user_id = q.created_by_user_id
                            WHERE q.workspace_id = ? AND q.created_by_user_id = ?
                              AND q.status = 'PENDING'
                              AND q.question_code = 'route.home-location'
                            """,
                            Boolean.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isTrue();
            verifyNoInteractions(routePlanner);

            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.TDX,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.TRANSIT,
                            Instant.parse("2026-08-05T01:00:00Z"),
                            Instant.parse("2026-08-05T01:40:00Z"),
                            Duration.ofMinutes(40),
                            Duration.ZERO,
                            null,
                            false,
                            true,
                            Instant.parse("2026-08-04T04:00:00Z"));
            when(routePlanner.plan(any())).thenReturn(
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.available(List.of(option), List.of()),
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.available(
                                    List.of(new com.aproject.aidriven.mymobilesecretary.planner
                                            .application.RoutePlanningResult.RouteOption(
                                            com.aproject.aidriven.mymobilesecretary.planner
                                                    .application.RoutePlanningResult.Provider.TDX,
                                            com.aproject.aidriven.mymobilesecretary.planner
                                                    .application.RoutePlanningRequest.TravelMode
                                                    .TRANSIT,
                                            Instant.parse("2026-08-06T01:00:00Z"),
                                            Instant.parse("2026-08-06T01:40:00Z"),
                                            Duration.ofMinutes(40),
                                            Duration.ZERO,
                                            null,
                                            false,
                                            true,
                                            Instant.parse("2026-08-04T04:00:00Z"))),
                                    List.of()));
            UUID answerRequest = UUID.randomUUID();

            IntentResult buffer = RequestCorrelationContext.run(
                    answerRequest, () -> intents.handle("我家", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    answerRequest, () -> intents.handle("我家", "TEST"));

            assertThat(replay.message()).isEqualTo(buffer.message());
            assertThat(buffer.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(buffer.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(buffer.message())
                    .startsWith("這次先以家裡為出發地。")
                    .contains("已替您安排", "我家", "TDX", "目前沒有與其他行程衝突")
                    .doesNotContain("沒有建立自訂地點", "workspace", "actor");
            assertThat(buffer.message().split(
                                    "https://www.google.com/maps/search/\\?api=1&query=", -1)
                            .length
                    - 1)
                    .isEqualTo(1);
            assertThat(buffer.message())
                    .doesNotContain(
                            "🗺️ Google Maps 路線規劃",
                            "https://www.google.com/maps/dir/?api=1");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
            assertThat(count("actor_location_preference", fixture.workspace())).isEqualTo(1);
            assertThat(jdbc.queryForMap(
                            """
                            SELECT location_kind, place_id, active, revision
                            FROM actor_location_preference
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .containsEntry("location_kind", "HOME")
                    .containsEntry("place_id", homeId)
                    .containsEntry("active", true)
                    .containsEntry("revision", 1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT COUNT(*) FROM conversation_pending_question
                            WHERE workspace_id = ? AND created_by_user_id = ?
                              AND question_code = 'route.home-location'
                              AND status = 'PENDING'
                            """,
                            Long.class,
                            fixture.workspace(),
                    fixture.context().actorId()))
                    .isZero();

            IntentResult declined = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("這次不用出發提醒", "TEST"));
            assertThat(declined.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);

            String nextText = "幫我規劃後天早上九點到桃園捷運「捷運台北車站」的行程";
            IntentResult reused = handle(
                    nextText,
                    new IntentCommand(
                            IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                            "再次到台北車站",
                            null,
                            "2026-08-06T09:00:00+08:00",
                            "2026-08-06T10:00:00+08:00",
                            "捷運台北車站",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            IntentOptions.empty(),
                            nextText));

            assertThat(reused.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(reused.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(reused.message())
                    .startsWith("這次先以家裡為出發地。")
                    .contains("已替您安排", "我家");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(2);
            assertThat(count("actor_location_preference", fixture.workspace())).isEqualTo(1);
        }
    }

    @Test
    void rideHailPersistsWaitBufferAndCreatesReplaySafeEarlyCallReminder() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "幫我規劃明天早上九點半搭計程車從捷運大坪林到捷運新店站";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "去捷運新店站",
                    null,
                    "2026-08-05T09:30:00+08:00",
                    null,
                    "捷運新店站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin("捷運大坪林", null),
                    text);
            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.GOOGLE,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.RIDE_HAIL,
                            Instant.parse("2026-08-05T01:30:00Z"),
                            Instant.parse("2026-08-05T01:38:00Z"),
                            Duration.ofMinutes(8),
                            Duration.ZERO,
                            4_500L,
                            true,
                            false,
                            Instant.parse("2026-08-04T04:00:00Z"));
            when(routePlanner.plan(any())).thenReturn(
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.available(List.of(option), List.of()));

            IntentResult created = handle(text, command);

            assertThat(created.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(created.nextQuestion().code()).isEqualTo("route.ride-hail-wait");
            assertThat(created.message())
                    .contains("09:30 ~ 09:38", "通常要預留幾分鐘等車")
                    .doesNotContain("出發前緩衝", "行程後緩衝");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verify(routePlanner).plan(any());

            IntentResult configured = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("8分鐘", "TEST"));

            assertThat(configured.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(configured.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(configured.message())
                    .contains(
                            "已設定等車緩衝", "8 分鐘",
                            "要依照等車時間設定提早叫車提醒嗎")
                    .doesNotContain("workspace", "actor", "UUID");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verifyNoMoreInteractions(routePlanner);
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isZero();
            assertThat(count("calendar_reminder_occurrence", fixture.workspace())).isZero();
            assertThat(jdbc.queryForMap(
                            """
                            SELECT general_before_minutes, general_after_minutes,
                                   ride_hail_wait_minutes, parking_minutes
                            FROM actor_route_operation_preference
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .containsEntry("general_before_minutes", null)
                    .containsEntry("general_after_minutes", null)
                    .containsEntry("ride_hail_wait_minutes", 8)
                    .containsEntry("parking_minutes", null);

            UUID reminderRequest = UUID.randomUUID();
            IntentResult reminded = RequestCorrelationContext.run(
                    reminderRequest, () -> intents.handle("好，請提醒我", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    reminderRequest, () -> intents.handle("好，請提醒我", "TEST"));

            assertThat(replay.message()).isEqualTo(reminded.message());
            assertThat(reminded.message()).contains("09:17提醒您叫車");
            assertThat(count("calendar_reminder_rule", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_reminder_occurrence", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT scheduled_at FROM calendar_reminder_occurrence
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            java.sql.Timestamp.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-08-05T01:17:00Z")));
        }
    }

    @Test
    void drivingPersistsParkingBufferInsideTheOperationalConflictWindow() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "幫我規劃明天早上九點半開車從捷運大坪林到捷運新店站";
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "開車去捷運新店站",
                    null,
                    "2026-08-05T09:30:00+08:00",
                    null,
                    "捷運新店站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin("捷運大坪林", null),
                    text);
            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.GOOGLE,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.DRIVE,
                            Instant.parse("2026-08-05T01:30:00Z"),
                            Instant.parse("2026-08-05T01:38:00Z"),
                            Duration.ofMinutes(8),
                            Duration.ZERO,
                            4_500L,
                            true,
                            false,
                            Instant.parse("2026-08-04T04:00:00Z"));
            when(routePlanner.plan(any())).thenReturn(
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.available(List.of(option), List.of()));

            IntentResult created = handle(text, command);
            assertThat(created.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(created.nextQuestion().code()).isEqualTo("route.parking-buffer");
            assertThat(created.message())
                    .contains("09:30 ~ 09:38", "通常要預留幾分鐘停車")
                    .doesNotContain("出發前緩衝", "行程後緩衝");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verify(routePlanner).plan(any());

            IntentResult configured = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("12分鐘", "TEST"));

            assertThat(configured.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(configured.nextQuestion().code()).isEqualTo("route.departure-reminder");
            assertThat(configured.message())
                    .contains("已設定停車緩衝", "12 分鐘")
                    .doesNotContain("workspace", "actor", "UUID");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            verifyNoMoreInteractions(routePlanner);
            assertThat(jdbc.queryForMap(
                            """
                            SELECT general_before_minutes, general_after_minutes,
                                   parking_minutes, ride_hail_wait_minutes
                            FROM actor_route_operation_preference
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .containsEntry("general_before_minutes", null)
                    .containsEntry("general_after_minutes", null)
                    .containsEntry("parking_minutes", 12)
                    .containsEntry("ride_hail_wait_minutes", null);
            assertThat(jdbc.queryForMap(
                            """
                            SELECT timed_start, timed_end, transport_mode
                            FROM calendar_intent_draft WHERE workspace_id = ?
                            """,
                            fixture.workspace()))
                    .containsEntry(
                            "timed_start",
                            java.sql.Timestamp.from(Instant.parse("2026-08-05T01:30:00Z")))
                    .containsEntry(
                            "timed_end",
                            java.sql.Timestamp.from(Instant.parse("2026-08-05T01:38:00Z")))
                    .containsEntry("transport_mode", "DRIVE");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "明天上午九點從高鐵台中站到桃園機場，幫我安排行程",
                "幫我規劃明天九點從捷運大坪林站到捷運新店站",
                "幫我安排明天9:30從台北車站出發到桃園機場",
                "幫我規劃明天早上九點從捷運大坪林到高鐵桃園站"
            })
    void explicitRouteTimeSurvivesAnIncorrectUnknownModelCommand(String text) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            stubDefaultTransitRoute();
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), text));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(text, "TEST"));

            assertThat(result.nextQuestion().code())
                    .isIn("route.departure-reminder", "route.transport-mode");
            if (result.nextQuestion().code().equals("route.departure-reminder")) {
                assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
                assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            } else {
                assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
                assertThat(count("calendar_plan", fixture.workspace())).isZero();
            }
            assertThat(result.message())
                    .doesNotContain("幾點出發", "活動預計多久", "幾點結束");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start IS NOT NULL FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            Boolean.class,
                            fixture.workspace()))
                    .isTrue();
        }
    }

    @Test
    void immediateRouteTimeIsRetainedWhenTheExplicitOriginIsNotConfirmed() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "幫我規劃待會3:30點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), text));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(text, "TEST"));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-offer");
            assertThat(result.message())
                    .contains("公司", "我找不到已儲存的地點", "這次要怎麼處理", "保留，開始新的操作")
                    .doesNotContain("幾點出發", "活動預計多久", "幾點結束");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start IS NOT NULL FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            Boolean.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isTrue();

            UUID cancelRequest = UUID.randomUUID();
            IntentResult canceled = RequestCorrelationContext.run(
                    cancelRequest, () -> intents.handle("取消", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    cancelRequest, () -> intents.handle("取消", "TEST"));

            assertThat(replay.message()).isEqualTo(canceled.message());
            assertThat(canceled.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(canceled.message())
                    .contains("已取消目前的子步驟", "從公司到高鐵桃園站", "這趟要從哪裡出發")
                    .doesNotContain("workflow", "UUID");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM route_place_creation_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            String.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isEqualTo("CANCELED");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(routePlanner);
        }
    }

    @Test
    void unknownRouteOriginCanStartTypedPlaceCreationChildWithoutLosingTheRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));

            IntentResult createOffer = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));
            assertThat(createOffer.nextQuestion().code()).isEqualTo("place.route-create-offer");
            assertThat(createOffer.message())
                    .contains("找不到已儲存的地點「公司」", "這次要怎麼處理", "保留，開始新的操作")
                    .doesNotContain("workflow", "UUID", "schema");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("place", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);

            IntentResult details = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("要建立新地點", "TEST"));
            assertThat(details.nextQuestion().code()).isEqualTo("place.route-create-details");
            assertThat(details.message())
                    .contains("建立出發地點「公司」", "地點名稱", "地址", "Google Maps 連結")
                    .doesNotContain("workflow", "UUID", "schema");
            when(googlePlacesClient.usable()).thenReturn(true);
            when(googlePlacesClient.searchFirst("內湖富邦大樓"))
                    .thenReturn(java.util.Optional.of(new GooglePlacesClient.PlaceCandidate(
                            "內湖富邦大樓",
                            "台北市內湖區成功路四段188號",
                            25.0836,
                            121.5945,
                            "辦公大樓")));

            IntentResult placeChild = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("內湖富邦大樓", "TEST"));

            assertThat(placeChild.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(placeChild.nextQuestion()).isNotNull();
            assertThat(placeChild.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(placeChild.message())
                    .contains(
                            "目前正在處理", "高鐵桃園站",
                            "建立出發地點", "公司", "內湖富邦大樓",
                            "1. 儲存為個人地點紀錄",
                            "2. 作為本次一次性地點",
                            "3. 取消建立地點")
                    .doesNotContain("這趟要從哪裡出發", "workflow", "UUID");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            """
                            SELECT timed_start IS NOT NULL FROM calendar_intent_draft
                            WHERE workspace_id = ? AND created_by_user_id = ?
                            """,
                            Boolean.class,
                            fixture.workspace(),
                            fixture.context().actorId()))
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "從公司出發",
                "由公司出發",
                "公司出發",
                "自公司啟程",
                "由公司動身",
                "從公司開始走",
                "從「公司」出發",
                "由 公司 啟程",
                "從公司這邊出發",
                "公司作為出發地",
                "公司為起點",
                "出發地點：公司"
            })
    void routePlaceOfferUsesOnlyThePlaceAliasFromAnOriginPhrase(String structuredOrigin) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司出發到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "公司到高鐵桃園站",
                    null,
                    "2026-08-12T05:15:00+08:00",
                    null,
                    "高鐵桃園站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin(structuredOrigin, null),
                    request));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-offer");
            assertThat(result.message())
                    .contains("地點「公司」", "這次要怎麼處理", "保留，開始新的操作")
                    .doesNotContain("地點「從公司出發」", "地點「由公司出發」", "地點「公司出發」");
            assertThat(jdbc.queryForObject(
                            "SELECT requested_alias FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("公司");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);
        }
    }

    @Test
    void routePlaceOfferAcceptsANaturalAliasDeclarationWithoutLeavingTheChildWorkflow() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceOffer();
            when(googlePlacesClient.usable()).thenReturn(true);
            when(googlePlacesClient.searchFirst("內湖富邦大樓"))
                    .thenReturn(java.util.Optional.of(new GooglePlacesClient.PlaceCandidate(
                            "內湖富邦大樓",
                            "台北市內湖區成功路四段188號",
                            25.0836,
                            121.5945,
                            "辦公大樓")));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("好，公司就是內湖富邦大樓", "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(result.message())
                    .contains(
                            "公司", "內湖富邦大樓",
                            "1. 儲存為個人地點紀錄",
                            "2. 作為本次一次性地點",
                            "3. 取消建立地點")
                    .doesNotContain("換個方式說明", "地址不一致", "待辦、行程，還是提醒");
            assertThat(jdbc.queryForMap(
                            "SELECT step, status FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("step", "CONFIRM")
                    .containsEntry("status", "PENDING");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void routePlaceOfferAcceptsADirectMapsLinkAsCandidateDetails() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceOffer();
            String mapsLink = "https://maps.app.goo.gl/company123";
            when(googlePlacesClient.resolveMapsLink(mapsLink)).thenReturn(java.util.Optional.of(
                    new GooglePlacesClient.PlaceCandidate(
                            "內湖富邦大樓",
                            "台北市內湖區成功路四段188號",
                            25.0836,
                            121.5945,
                            "辦公大樓")));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(mapsLink, "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(result.message())
                    .contains(
                            "內湖富邦大樓",
                            "1. 儲存為個人地點紀錄",
                            "2. 作為本次一次性地點",
                            "3. 取消建立地點")
                    .doesNotContain(mapsLink, "待辦、行程，還是提醒");
            assertThat(jdbc.queryForObject(
                            "SELECT place_query FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("內湖富邦大樓");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void routePlaceOfferAcceptsADirectPlaceNameWithoutAnExtraPrompt() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceOffer();
            when(googlePlacesClient.usable()).thenReturn(true);
            when(googlePlacesClient.searchFirst("內湖富邦大樓"))
                    .thenReturn(java.util.Optional.of(new GooglePlacesClient.PlaceCandidate(
                            "內湖富邦大樓",
                            "台北市內湖區成功路四段188號",
                            25.0836,
                            121.5945,
                            "辦公大樓")));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("內湖富邦大樓", "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(result.message())
                    .contains(
                            "公司", "內湖富邦大樓",
                            "1. 儲存為個人地點紀錄",
                            "2. 作為本次一次性地點",
                            "3. 取消建立地點")
                    .doesNotContain("請提供「公司」", "待辦、行程，還是提醒");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void incompatiblePlaceDetailsAnswerNamesTheCurrentStageAndPreservesProgress() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceOffer();
            RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("建立地點", "TEST"));
            interpreter.nextCommand(context(
                    IntentCommand.Type.UNKNOWN, "這不是我要處理的內容"));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(),
                    () -> intents.handle("這不是我要處理的內容", "TEST"));

            assertThat(result.message())
                    .contains(
                            "這個指令不是目前階段可處理的答案",
                            "尚未修改資料",
                            "目前正在處理",
                            "建立出發地點「公司」",
                            "保留，開始新的操作",
                            "請提供「公司」")
                    .doesNotContain("換個方式說明", "待辦、行程，還是提醒");
            assertThat(jdbc.queryForMap(
                            "SELECT step, status FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("step", "DETAILS")
                    .containsEntry("status", "PENDING");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);
        }
    }

    @Test
    void completeNewScheduleDuringRoutePlaceOfferIsRetainedAndActivatedWithoutRepeatingIt() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceOffer();
            String newRequest = "幫我建立今天中午十二點跟同事聚餐的行程";
            interpreter.nextCommand(create("跟同事聚餐", newRequest));

            IntentResult choice = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(newRequest, "TEST"));

            assertThat(choice.nextQuestion().code()).isEqualTo("conversation.context-target");
            assertThat(choice.message())
                    .contains("從公司到高鐵桃園站", "保留", "繼續", "開始新的")
                    .doesNotContain("請提供「公司」", "已建立行程");
            assertThat(jdbc.queryForMap(
                            "SELECT step, status FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("step", "OFFER")
                    .containsEntry("status", "PENDING");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);

            IntentResult activated = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("保留，開始新的操作", "TEST"));

            assertThat(activated.message())
                    .contains("原本的「從公司到高鐵桃園站」先保留", "跟同事聚餐", "尚未放進行事曆")
                    .doesNotContain("請告訴我要開始的新操作內容", "請再說一次");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_intent_draft WHERE workspace_id = ? AND status = 'PENDING'",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(2L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "從我目前的位置出發",
        "從我現在的位置出發",
        "由我的位置出發",
        "以目前位置為起點",
        "就從這裡出發",
        "從這邊走"
    })
    void currentLocationRequestsOneTimeMapsEvidenceWithoutOfferingToCreateAPlace(
            String structuredOrigin) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點" + structuredOrigin + "到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "目前位置到高鐵桃園站",
                    null,
                    "2026-08-12T05:15:00+08:00",
                    null,
                    "高鐵桃園站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin(structuredOrigin, null),
                    request));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-details");
            assertThat(result.message())
                    .contains("暫時出發位置", "Google Maps 連結", "只供本次路線使用", "不會儲存")
                    .doesNotContain("要建立新的地點嗎", "地點「從我目前的位置出發」");
            assertThat(jdbc.queryForObject(
                            "SELECT requested_alias FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("目前位置");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("place_alias", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);
        }
    }

    @Test
    void currentLocationMapsLinkIsUsedOnceWithoutCreatingAPermanentPlace() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String mapsLink = "https://maps.app.goo.gl/current123";
            String request = "幫我規劃待會5:15點從這裡（" + mapsLink + "）出發到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                    "目前位置到高鐵桃園站",
                    null,
                    "2026-08-12T05:15:00+08:00",
                    null,
                    "高鐵桃園站",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withDepartureOrigin("這裡", null),
                    request));
            when(googlePlacesClient.resolveMapsLink(mapsLink)).thenReturn(java.util.Optional.of(
                    new GooglePlacesClient.PlaceCandidate(
                            "目前分享位置", null, 25.0330, 121.5654, "POINT")));
            when(routePlanner.plan(any())).thenReturn(
                    com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .insufficient(List.of(
                                    com.aproject.aidriven.mymobilesecretary.planner.application
                                            .RoutePlanningResult.ProviderFailure.TDX_FAILED,
                                    com.aproject.aidriven.mymobilesecretary.planner.application
                                            .RoutePlanningResult.ProviderFailure.GOOGLE_FAILED)));

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));

            assertThat(result.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(result.message())
                    .contains("這次只使用「目前分享位置」作為出發地", "不會儲存")
                    .doesNotContain(mapsLink, "要建立新的地點嗎", "儲存並繼續");
            assertThat(jdbc.queryForMap(
                            "SELECT requested_alias, place_query, status FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("requested_alias", "目前位置")
                    .containsEntry("place_query", "目前分享位置")
                    .containsEntry("status", "COMPLETED");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("place_alias", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(googlePlacesClient).resolveMapsLink(mapsLink);
            verify(routePlanner).plan(any());
        }
    }

    @Test
    void unavailablePlaceProviderKeepsTheParentRouteRecoverableWithoutMutation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));
            IntentResult origin = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));
            assertThat(origin.nextQuestion().code()).isEqualTo("place.route-create-offer");
            IntentResult details = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("要建立新地點", "TEST"));
            assertThat(details.nextQuestion().code()).isEqualTo("place.route-create-details");
            when(googlePlacesClient.usable()).thenReturn(false);

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("內湖富邦大樓", "TEST"));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.message())
                    .contains("目前無法安全確認您提供的位置", "尚未建立地點或行程", "更完整")
                    .doesNotContain("exception", "workflow", "UUID");
            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-details");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("PENDING");
            assertThat(count("route_place_creation_draft", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForMap(
                            "SELECT status, step FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("status", "PENDING")
                    .containsEntry("step", "DETAILS");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void unresolvedMapsLinkOfferedForUnknownOriginMovesTheChildToDetailsWithoutMutation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));
            IntentResult origin = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));
            assertThat(origin.nextQuestion().code()).isEqualTo("place.route-create-offer");
            String mapsLink = "https://maps.app.goo.gl/GenericLocation?g_st=ic";
            when(googlePlacesClient.resolveMapsLink(mapsLink)).thenReturn(java.util.Optional.empty());

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(mapsLink, "TEST"));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.nextQuestion().code()).isEqualTo("place.route-create-details");
            assertThat(jdbc.queryForMap(
                            "SELECT status, step FROM route_place_creation_draft WHERE workspace_id = ?",
                            fixture.workspace()))
                    .containsEntry("status", "PENDING")
                    .containsEntry("step", "DETAILS");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(googlePlacesClient).resolveMapsLink(mapsLink);
        }
    }

    @Test
    void routePlaceDetailsAcceptGoogleMapsLinkWithoutPersistingTheRawUrl() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));
            IntentResult offer = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));
            assertThat(offer.nextQuestion().code()).isEqualTo("place.route-create-offer");
            IntentResult details = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("要建立新的地點", "TEST"));
            assertThat(details.nextQuestion().code()).isEqualTo("place.route-create-details");
            assertThat(details.message()).contains("Google Maps 連結");

            String mapsLink = "https://www.google.com/maps/place/Neihu+Office/@25.0836,121.5945,17z?"
                    + "entry=trusted-share&".repeat(20);
            assertThat(mapsLink.length()).isGreaterThan(300);
            when(googlePlacesClient.resolveMapsLink(mapsLink)).thenReturn(java.util.Optional.of(
                    new GooglePlacesClient.PlaceCandidate(
                            "內湖富邦大樓", null, 25.0836, 121.5945, "OFFICE")));

            IntentResult confirmation = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(mapsLink, "TEST"));

            assertThat(confirmation.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(confirmation.message()).contains("內湖富邦大樓").doesNotContain(mapsLink);
            assertThat(jdbc.queryForObject(
                            "SELECT place_query FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("內湖富邦大樓");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verify(googlePlacesClient).resolveMapsLink(mapsLink);
            verifyNoMoreInteractions(googlePlacesClient);
        }
    }

    @Test
    void decliningUnknownRecordedPlaceReturnsToTheParentRouteWithoutMutation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
            interpreter.nextCommand(new IntentCommand(
                    IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                    "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));
            IntentResult offer = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(request, "TEST"));
            assertThat(offer.nextQuestion().code()).isEqualTo("place.route-create-offer");

            IntentResult declined = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("不要建立", "TEST"));

            assertThat(declined.message())
                    .contains("已取消建立地點「公司」", "原本的路線規劃仍保留", "哪個已確認地點出發")
                    .doesNotContain("workflow", "UUID", "schema");
            assertThat(declined.nextQuestion().code()).isEqualTo("route.origin-context");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("CANCELED");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            verifyNoInteractions(googlePlacesClient, routePlanner);
        }
    }

    @Test
    void routePlaceChildStatusIsReadOnlyAndKeepsTheActionableQuestion() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("工作地點位於內湖富邦大樓");

            long childRevision = jdbc.queryForObject(
                    "SELECT revision FROM route_place_creation_draft WHERE workspace_id = ?",
                    Long.class,
                    fixture.workspace());
            IntentResult status = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("處理到哪了？", "TEST"));

            assertThat(status.message())
                    .contains(
                            "目前正在處理", "高鐵桃園站",
                            "目前子步驟：決定「工作」的地點紀錄類別",
                            "父流程「從工作到高鐵桃園站」仍保留",
                            "路線尚未查詢", "行程尚未完成建立",
                            "請選擇這個地點的使用方式",
                            "1. 儲存為個人地點紀錄",
                            "2. 作為本次一次性地點",
                            "3. 取消建立地點")
                    .doesNotContain("要「儲存並繼續」", "還是「取消建立地點」")
                    .doesNotContain("workflow", "UUID", "route_place_creation_draft");
            assertThat(status.nextQuestion().code()).isEqualTo("place.route-create-confirm");
            assertThat(jdbc.queryForObject(
                            "SELECT revision FROM route_place_creation_draft WHERE workspace_id = ?",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(childRevision);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM conversation_pending_question "
                                    + "WHERE workspace_id = ? AND status = 'PENDING'",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("PENDING");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("place", fixture.workspace())).isZero();
        }
    }

    @Test
    void savingRoutePlaceChildResumesTheParentExactlyOnce() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司位置在內湖富邦大樓");
            stubDefaultTransitRoute();
            UUID confirmationRequest = UUID.randomUUID();

            IntentResult saved = RequestCorrelationContext.run(
                    confirmationRequest, () -> intents.handle("儲存並繼續", "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    confirmationRequest, () -> intents.handle("儲存並繼續", "TEST"));

            assertThat(replay.message()).isEqualTo(saved.message());
            assertThat(saved.message())
                    .contains("儲存為「公司」", "高鐵站「桃園」")
                    .doesNotContain("這趟要從哪裡出發", "還不能確認出發地點");
            assertThat(count("place", fixture.workspace())).isEqualTo(1L);
            assertThat(count("place_alias", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("COMPLETED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "儲存地點", "保存這個地點", "把這個地點存起來"})
    void routePlaceSavePhrasesCannotEscapeToGenericParentConfirmation(String phrase) {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司位置在內湖富邦大樓");
            stubDefaultTransitRoute();

            IntentResult saved = handle(
                    phrase, context(IntentCommand.Type.ACCEPT_CONTEXT, phrase));

            assertThat(saved.message())
                    .contains(
                            "儲存為「公司」",
                            "本次行程：",
                            "🏷️ ",
                            "📅 ",
                            "🕒 ",
                            "📍",
                            "高鐵站「桃園」")
                    .doesNotContain("目前無法再繼續處理");
            assertThat(saved.responseEnvelope().message())
                    .contains("儲存為「公司」", "要依照行程長度設定出發提醒嗎")
                    .doesNotContain("目前無法再繼續處理");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("COMPLETED");
            assertThat(count("place", fixture.workspace())).isEqualTo(1L);
            assertThat(count("place_alias", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        }
    }

    @Test
    void savingRoutePlaceChildAfterParentAlreadyMaterializedReconcilesWithoutAnotherPlan() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司位置在內湖富邦大樓");
            stubDefaultTransitRoute();

            UUID parentId = jdbc.queryForObject(
                    "SELECT parent_calendar_draft_id FROM route_place_creation_draft "
                            + "WHERE workspace_id = ? AND status = 'PENDING'",
                    UUID.class,
                    fixture.workspace());
            var parent = drafts.get(parentId);
            drafts.materialize(parent.id(), parent.revision());
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);

            IntentResult reconciled = handle(
                    "1", context(IntentCommand.Type.ACCEPT_CONTEXT, "1"));

            assertThat(reconciled.message())
                    .contains(
                            "儲存為「公司」個人地點紀錄",
                            "好的，已替您安排",
                            "本次行程：",
                            "🏷️ ",
                            "🕒 ",
                            "｜出發",
                            "｜抵達",
                            "TDX 路線資料")
                    .doesNotContain(
                            "先前已建立",
                            "原本的行程",
                            "本次沒有再次建立或修改行程",
                            "處理結果目前還無法確認",
                            "目前沒有可接受的行程提案");
            verify(routePlanner).plan(any());
            assertThat(count("place", fixture.workspace())).isEqualTo(1L);
            assertThat(count("place_alias", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT placement_kind FROM calendar_intent_draft "
                                    + "WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("TIMED_INTERVAL");
            assertThat(jdbc.queryForObject(
                            "SELECT route_provider_status FROM calendar_intent_draft "
                                    + "WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_time_node "
                                    + "WHERE workspace_id = ? AND cancellation_status = 'ACTIVE'",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM conversation_pending_question "
                                    + "WHERE workspace_id = ? AND status = 'PENDING'",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT question_code FROM conversation_pending_question "
                                    + "WHERE workspace_id = ? AND status = 'PENDING'",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("route.departure-reminder");
        }
    }

    @Test
    void prematureMaterializedRouteRemainsRecoverableWhenProviderIsTemporarilyUnavailable() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司位置在內湖富邦大樓");
            stubUnavailableThenDefaultTransitRoute();

            UUID parentId = jdbc.queryForObject(
                    "SELECT parent_calendar_draft_id FROM route_place_creation_draft "
                            + "WHERE workspace_id = ? AND status = 'PENDING'",
                    UUID.class,
                    fixture.workspace());
            var parent = drafts.get(parentId);
            drafts.materialize(parent.id(), parent.revision());

            IntentResult unavailable = handle(
                    "1", context(IntentCommand.Type.ACCEPT_CONTEXT, "1"));

            assertThat(unavailable.message())
                    .contains("目前暫時查不到可採用的路線", "之後再查路線")
                    .doesNotContain("已替您安排", "已建立行程", "先前已建立");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT route_provider_status FROM calendar_intent_draft "
                                    + "WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("UNAVAILABLE");

            IntentResult retained = handle(
                    "保留", context(IntentCommand.Type.ACCEPT_CONTEXT, "保留"));
            assertThat(retained.message())
                    .contains("已保留這份待確認安排", "目前尚未建立行程");

            IntentResult completed = handle(
                    "再查路線", context(IntentCommand.Type.ACCEPT_CONTEXT, "再查路線"));
            assertThat(completed.message())
                    .contains(
                            "好的，已替您安排",
                            "本次行程：",
                            "｜出發",
                            "｜抵達",
                            "TDX 路線資料")
                    .doesNotContain("先前已建立", "原本的行程");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_time_node "
                                    + "WHERE workspace_id = ? AND cancellation_status = 'ACTIVE'",
                            Long.class,
                            fixture.workspace()))
                    .isEqualTo(2L);
        }
    }

    @Test
    void savingRoutePlaceChildReturnsToUnfinishedParentWithoutCalendarMutation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司位置在內湖富邦大樓");
            stubUnavailableThenDefaultTransitRoute();

            IntentResult resumed = handle(
                    "儲存地點", context(IntentCommand.Type.ACCEPT_CONTEXT, "儲存地點"));

            assertThat(resumed.message())
                    .contains("儲存為「公司」", "目前暫時查不到可採用的路線", "之後再查路線")
                    .doesNotContain("已把", "放進行事曆", "目前無法再繼續處理");
            assertThat(resumed.nextQuestion().code()).isEqualTo("route.provider-unavailable");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("PENDING");
            assertThat(count("place", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
        }
    }

    @Test
    void oneTimeRoutePlaceUseResumesWithoutSavingCustomPlace() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("辦公室就在內湖富邦大樓");
            stubDefaultTransitRoute();

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("只用這次", "TEST"));

            assertThat(result.message())
                    .contains("這次只使用「內湖富邦大樓」作為出發地", "沒有儲存自訂地點")
                    .doesNotContain("這趟要從哪裡出發");
            assertThat(count("place", fixture.workspace())).isZero();
            assertThat(count("place_alias", fixture.workspace())).isZero();
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        }
    }

    @Test
    void cancelingRoutePlaceChildReturnsToThePreservedParentRoute() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司地點在內湖富邦大樓");

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("取消", "TEST"));

            assertThat(result.message())
                    .contains(
                            "已取消目前的子步驟", "高鐵桃園站",
                            "目前正在處理", "目前進度", "下一步", "從哪裡出發")
                    .doesNotContain("已取消未完成的路線規劃", "workflow", "UUID");
            assertThat(result.nextQuestion().code()).isEqualTo("route.origin-context");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("PENDING");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("place", fixture.workspace())).isZero();
        }
    }

    @Test
    void cancelingTheWholeRouteClosesBothChildAndParent() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            beginRoutePlaceChild("公司地點在內湖富邦大樓");

            IntentResult result = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("取消整個行程規劃", "TEST"));

            assertThat(result.message())
                    .contains("已取消整個未完成的行程規劃", "已建立的行程沒有變更")
                    .doesNotContain("下一步", "從哪裡出發", "workflow", "UUID");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM route_place_creation_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM calendar_intent_draft WHERE workspace_id = ?",
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("DISCARDED");
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(count("place", fixture.workspace())).isZero();
        }
    }

    @Test
    void parallelRoutePlaceChildrenRemainActorAndWorkspaceIsolated() {
        Fixture first = fixture();
        Fixture second = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(first.context())) {
            beginRoutePlaceChild("公司地點在內湖富邦大樓");
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(second.context())) {
            beginRoutePlaceChild("工作地點位於內湖富邦大樓");
        }

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(first.context())) {
            IntentResult firstStatus = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("目前進度是什麼？", "TEST"));
            assertThat(firstStatus.message())
                    .contains("決定「公司」的地點紀錄類別")
                    .doesNotContain("決定「工作」的地點紀錄類別");
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(second.context())) {
            IntentResult secondStatus = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle("現在在處理哪個項目？", "TEST"));
            assertThat(secondStatus.message())
                    .contains("決定「工作」的地點紀錄類別")
                    .doesNotContain("決定「公司」的地點紀錄類別");
        }
    }

    @Test
    void routeActualEntryTwentySampleP95StaysBoundedWithExactMutation() {
        List<Long> elapsedMilliseconds = new ArrayList<>();
        for (int sample = 0; sample < 20; sample++) {
            Fixture fixture = fixture();
            try (WorkspaceContextHolder.Scope ignored =
                    WorkspaceContextHolder.open(fixture.context())) {
                String text = "9月10日上午九點從高鐵台中站到桃園機場參加會議，幫我安排行程";
                IntentCommand command = new IntentCommand(
                        IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                        "參加會議", null,
                        "2026-09-10T09:00:00+08:00",
                        "2026-09-10T10:00:00+08:00",
                        "桃園機場", null, null, null, null, null, null, false,
                        IntentOptions.empty().withDepartureOrigin("高鐵台中站", null), text);

                long started = System.nanoTime();
                IntentResult result = handle(text, command);
                elapsedMilliseconds.add((System.nanoTime() - started) / 1_000_000L);

                assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
                assertThat(result.nextQuestion().code()).isEqualTo("route.transport-offer");
                assertThat(result.message()).doesNotContain(
                        "PLAN_ROUTE_ITINERARY", "Intent", "workspace", "actor");
                assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1);
                assertThat(count("calendar_activity", fixture.workspace())).isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM calendar_time_node
                        WHERE workspace_id = ? AND node_key = 'transport-departure'
                        """, Long.class, fixture.workspace())).isZero();
            }
        }
        List<Long> sorted = elapsedMilliseconds.stream().sorted().toList();
        System.out.printf("ROUTE_ACTUAL_ENTRY_SAMPLES_MS=%s%n", elapsedMilliseconds);
        assertThat(sorted.get(18)).as("route actual-entry P95 milliseconds")
                .isLessThanOrEqualTo(1_000L);
    }

    private IntentResult beginRoutePlaceChild(String declaration) {
        String alias = declaration.startsWith("工作")
                ? "工作"
                : declaration.startsWith("辦公室") ? "辦公室" : "公司";
        String request = "幫我規劃待會5:15點從%s到高鐵桃園站".formatted(alias);
        interpreter.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "資訊不足", null, null, null, null, null, IntentOptions.empty(), request));
        IntentResult origin = RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(request, "TEST"));
        assertThat(origin.nextQuestion().code()).isEqualTo("place.route-create-offer");
        IntentResult details = RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle("要建立新地點", "TEST"));
        assertThat(details.nextQuestion().code()).isEqualTo("place.route-create-details");
        when(googlePlacesClient.usable()).thenReturn(true);
        when(googlePlacesClient.searchFirst("內湖富邦大樓"))
                .thenReturn(java.util.Optional.of(new GooglePlacesClient.PlaceCandidate(
                        "內湖富邦大樓",
                        "台北市內湖區成功路四段188號",
                        25.0836,
                        121.5945,
                        "辦公大樓")));
        IntentResult child = RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle("內湖富邦大樓", "TEST"));
        assertThat(child.nextQuestion().code()).isEqualTo("place.route-create-confirm");
        return child;
    }

    private IntentResult beginRoutePlaceOffer() {
        String request = "幫我規劃待會5:15點從公司到高鐵桃園站";
        interpreter.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                "資訊不足",
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty(),
                request));
        IntentResult origin = RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(request, "TEST"));
        assertThat(origin.nextQuestion().code()).isEqualTo("place.route-create-offer");
        return origin;
    }

    private IntentResult handle(String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(text, "TEST"));
    }

    private void stubDefaultTransitRoute() {
        when(routePlanner.plan(any())).thenAnswer(invocation -> {
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                    request = invocation.getArgument(0);
            Duration duration = Duration.ofMinutes(8);
            Instant depart = request.timeRole()
                            == com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TimeRole.DEPART_AT
                    ? request.time()
                    : request.time().minus(duration);
            Instant arrive = depart.plus(duration);
            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.TDX,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.TRANSIT,
                            depart,
                            arrive,
                            duration,
                            Duration.ZERO,
                            null,
                            false,
                            true,
                            Instant.parse("2026-08-04T04:00:00Z"));
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.available(List.of(option), List.of());
        });
    }

    private void stubUnavailableThenDefaultTransitRoute() {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(routePlanner.plan(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.insufficient(List.of(
                                com.aproject.aidriven.mymobilesecretary.planner.application
                                        .RoutePlanningResult.ProviderFailure.TDX_UNAVAILABLE));
            }
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                    request = invocation.getArgument(0);
            Duration duration = Duration.ofMinutes(8);
            Instant depart = request.time();
            var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption(
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningResult.Provider.TDX,
                            com.aproject.aidriven.mymobilesecretary.planner.application
                                    .RoutePlanningRequest.TravelMode.TRANSIT,
                            depart,
                            depart.plus(duration),
                            duration,
                            Duration.ZERO,
                            null,
                            false,
                            true,
                            Instant.parse("2026-08-04T04:00:00Z"));
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.available(List.of(option), List.of());
        });
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'H4 actual entry', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'H4 actual entry', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
        return new Fixture(
                workspace,
                new WorkspaceContext(
                        actor, workspace, WorkspaceChannel.TEST, "h4-entry", "conversation"));
    }

    private long count(String table, UUID workspace) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspace);
    }

    private static IntentCommand create(String title, String source) {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                "2026-09-10T15:00:00+08:00",
                "2026-09-10T16:00:00+08:00",
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

    private static IntentCommand context(IntentCommand.Type type, String source) {
        return new IntentCommand(
                type,
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

    private static IntentCommand standaloneRouteCommand(
            String title,
            String start,
            String end,
            String destination,
            String origin,
            String source) {
        return new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY,
                title,
                null,
                start,
                end,
                destination,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty().withDepartureOrigin(origin, null),
                source);
    }

    private record Fixture(UUID workspace, WorkspaceContext context) {}
}
