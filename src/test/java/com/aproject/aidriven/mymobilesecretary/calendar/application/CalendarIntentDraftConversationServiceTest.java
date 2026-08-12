package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteAssessment;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationPendingQuestionService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CalendarIntentDraftConversationServiceTest {

    @ParameterizedTest
    @CsvSource({
        "DRIVE, driving",
        "RIDE_HAIL, driving",
        "TWO_WHEELER, two-wheeler",
        "WALK, walking",
        "TRANSIT, transit"
    })
    void googleMapsRouteLinkUsesTheVerifiedTypedTravelMode(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TravelMode
                    mode,
            String expected) {
        assertThat(CalendarIntentDraftConversationService.googleMapsTravelMode(mode))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "'前10分鐘，後15分鐘', 10, 15",
        "'前面留10分鐘，後面留15分鐘', 10, 15",
        "'出發前預留10分鐘，抵達後預留15分鐘', 10, 15",
        "'前後各10分鐘', 10, 10"
    })
    void connectionBufferAcceptsCommonNaturalPhrasings(
            String answer, int expectedBefore, int expectedAfter) {
        assertThat(CalendarIntentDraftConversationService.generalBufferMinutes(answer))
                .containsExactly(expectedBefore, expectedAfter);
    }

    @Test
    void connectionBufferRejectsOutOfRangeMinutes() {
        assertThat(CalendarIntentDraftConversationService.generalBufferMinutes(
                        "前241分鐘，後15分鐘"))
                .isNull();
    }

    @Test
    void connectionBufferKeepsSafeAdjustmentWithinSixHours() {
        var preference = new RouteOperationPreferenceService.View(120, 15, null, null, 1);

        assertThat(CalendarIntentDraftConversationService.boundedConnectionShift(
                        Duration.ofMinutes(20), preference))
                .isEqualTo(Duration.ofMinutes(140));
        assertThat(CalendarIntentDraftConversationService.boundedConnectionShift(
                        Duration.ofHours(5), preference))
                .isNull();
    }

    @org.junit.jupiter.api.Test
    void routeRecurrenceUsesTypedScanFriendlyLabels() {
        assertThat(CalendarIntentDraftConversationService.routeRecurrenceLine("WEEKLY", null))
                .isEqualTo("🔁 重複：每週\n");
        assertThat(CalendarIntentDraftConversationService.routeRecurrenceLine(
                        "WEEKDAYS", java.time.LocalDate.of(2026, 12, 31)))
                .isEqualTo("🔁 重複：每個平日，到 2026-12-31 為止\n");
        assertThat(CalendarIntentDraftConversationService.routeRecurrenceLine(null, null))
                .isEmpty();
    }

    @Test
    void orphanRouteQuestionDoesNotThrowWhenItsWorkflowIsNotACalendarDraft() {
        ConversationPendingQuestionService pendingQuestions =
                mock(ConversationPendingQuestionService.class);
        ConversationPendingQuestion pending = mock(ConversationPendingQuestion.class);
        UUID unrelatedWorkflow = UUID.randomUUID();
        when(pending.getQuestionCode()).thenReturn("route.activity-duration");
        when(pending.getWorkflowId()).thenReturn(unrelatedWorkflow);
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        service.setPendingQuestions(pendingQuestions);
        when(drafts.get(unrelatedWorkflow)).thenThrow(new
                com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException(
                        "Calendar intent draft", "requested draft"));

        assertThatCode(() -> service.answerTransportPlanning("繼續"))
                .doesNotThrowAnyException();
        assertThat(service.answerTransportPlanning("繼續")).isEmpty();
    }

    private CalendarIntentDraftService drafts;
    private ConversationFocusService focuses;
    private CalendarIntentDraftConversationService service;

    @BeforeEach
    void setUp() {
        drafts = mock(CalendarIntentDraftService.class);
        focuses = mock(ConversationFocusService.class);
        service = new CalendarIntentDraftConversationService(
                drafts, focuses,
                mock(CalendarIntentDraftPreflightResponsePolicy.class),
                mock(com.aproject.aidriven.mymobilesecretary.conversation.application
                                .ConversationOperationCompletionGate.class));
    }

    @Test
    void completeRouteCreatesActivityThenOffersOptionalTransport() {
        IntentCommand command = routeCommand();
        var pending = view(CalendarIntentDraftService.Status.PENDING,
                CalendarIntentDraftService.TransportOfferStatus.NONE, null, 1);
        var saved = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.NONE, null, 2);
        when(drafts.propose(command)).thenReturn(pending);
        when(drafts.confirm(pending.id(), pending.revision()))
                .thenReturn(CalendarIntentDraftService.ConfirmationResult.completed(saved));
        when(drafts.offerTransportForCurrentRequest()).thenReturn(view(
                CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.OFFERED, null, 3));

        IntentResult result = service.createRouteWithPreflight(command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
        assertThat(result.nextQuestion().code()).isEqualTo("route.transport-offer");
        assertThat(result.message()).contains("已建立行程", "要不要我也幫你規劃交通方式");
        verify(drafts).offerTransportForCurrentRequest();
    }

    @Test
    void standaloneOriginDestinationTripPlansRouteOnTheFirstTurn() {
        String source = "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程";
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY, "大坪林到台北車站", null,
                "2026-08-05T09:00:00+08:00", "2026-08-05T10:00:00+08:00",
                "桃園捷運「捷運台北車站」", null, null, null, null, null, null,
                null, IntentOptions.empty().withDepartureOrigin("捷運大坪林", null), source);
        var pending = pointView(CalendarIntentDraftService.Status.PENDING,
                CalendarIntentDraftService.TransportOfferStatus.NONE, 1);
        var prepared = standaloneView(CalendarIntentDraftService.Status.PENDING, 2);
        var saved = standaloneView(CalendarIntentDraftService.Status.MATERIALIZED, 3);
        when(drafts.propose(command)).thenReturn(pending);
        var planner = mock(com.aproject.aidriven.mymobilesecretary.planner.application
                .ProviderNeutralRouteService.class);
        service.setRoutePlanner(planner);
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        when(preferences.current()).thenReturn(Optional.of(
                new RouteOperationPreferenceService.View(11, 19, null, null, 1)));
        service.setRouteOperationPreferences(preferences);
        var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningResult.RouteOption(
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningResult.Provider.TDX,
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.TRANSIT,
                        Instant.parse("2026-08-05T01:00:00Z"),
                        Instant.parse("2026-08-05T01:40:00Z"), Duration.ofMinutes(40),
                        Duration.ZERO, null, false, true,
                        Instant.parse("2026-08-04T04:00:00Z"),
                        java.util.List.of(new com.aproject.aidriven.mymobilesecretary.planner
                                .application.RoutePlanningResult.TransitLeg(
                                        "MRT", "松山新店線", "松山",
                                        "捷運大坪林站", "捷運台北車站")));
        when(planner.plan(any())).thenReturn(
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.available(java.util.List.of(option), java.util.List.of()));
        when(drafts.prepareStandaloneRoute(
                        pending.id(), pending.revision(),
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.TRANSIT,
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TimeRole.DEPART_AT,
                        option))
                .thenReturn(prepared);
        when(drafts.confirmStandaloneRoute(prepared.id(), prepared.revision()))
                .thenReturn(CalendarIntentDraftService.ConfirmationResult.completed(saved));

        IntentResult result = service.createRouteWithPreflight(command);

        verify(planner).plan(any());
        assertThat(result.nextQuestion().code()).isEqualTo("route.departure-reminder");
        assertThat(result.message())
                .contains(
                        "好的，已替您安排✅", "本次行程：", "🏷️ 去捷運台北車站（桃園機場捷運）",
                        "📅 2026年8月5日（三）", "🕒 09:00 ~ 09:40",
                        "09:00 📍捷運大坪林站｜出發", "⬇️ 行程約 40 分鐘（TDX 路線資料）",
                        "09:40 📍捷運台北車站（桃園機場捷運）｜抵達",
                        "交通方式", "捷運大坪林站", "松山新店線", "往「松山」", "捷運台北車站",
                        "要依照行程長度設定出發提醒嗎")
                .contains(
                        "【Google Maps 地點】",
                        "https://www.google.com/maps/search/?api=1&query=24.98275%2C121.54145",
                        "https://www.google.com/maps/search/?api=1&query=25.04869%2C121.51428",
                        "🗺️ Google Maps 路線規劃",
                        "https://www.google.com/maps/dir/?api=1&origin=24.98275%2C121.54145"
                                + "&destination=25.04869%2C121.51428&travelmode=transit")
                .doesNotContain(
                        "08:49", "09:59", "出發前緩衝開始", "行程後緩衝結束",
                        "要不要我也幫你規劃交通方式", "共構場站中心定位", "沒有建立自訂地點");
    }

    @Test
    void routeRiskListsEveryAffectedExistingTripBeforeOfferingAChoice() {
        String source = "幫我規劃明天早上九點半從捷運大坪林到捷運新店站";
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY, "去捷運新店站", null,
                "2026-08-05T09:30:00+08:00", null, "捷運新店站",
                null, null, null, null, null, null, null,
                IntentOptions.empty().withDepartureOrigin("捷運大坪林", null), source);
        var pending = pointView(CalendarIntentDraftService.Status.PENDING,
                CalendarIntentDraftService.TransportOfferStatus.NONE, 1);
        var prepared = standaloneView(CalendarIntentDraftService.Status.PENDING, 2);
        var saved = standaloneView(CalendarIntentDraftService.Status.MATERIALIZED, 3);
        var planner = mock(com.aproject.aidriven.mymobilesecretary.planner.application
                .ProviderNeutralRouteService.class);
        service.setRoutePlanner(planner);
        var option = routeOption();
        when(drafts.propose(command)).thenReturn(pending);
        when(planner.plan(any())).thenReturn(
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.available(java.util.List.of(option), java.util.List.of()));
        when(drafts.prepareStandaloneRoute(
                pending.id(), pending.revision(),
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TravelMode.TRANSIT,
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TimeRole.DEPART_AT,
                option)).thenReturn(prepared);
        var existing = new PersonalRouteConstraint(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "去捷運台北車站（桃園機場捷運）",
                Instant.parse("2026-08-05T01:26:00Z"),
                new CalendarLocation("捷運台北車站（桃園機場捷運）", 25.04869, 121.51428),
                Adjustability.LOCKED, 1);
        var candidate = new PersonalRouteConstraint(
                saved.id(), UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                existing.sourceCreatedByUserId(), "去捷運新店站",
                Instant.parse("2026-08-05T01:30:00Z"),
                new CalendarLocation("捷運大坪林站", 24.98275, 121.54145),
                Adjustability.LOCKED, 3);
        var risk = new PersonalRouteAssessment(
                PersonalRouteStatus.IMPOSSIBLE, existing, candidate,
                Duration.ofMinutes(36), Duration.ofMinutes(4));
        var preflight = new CalendarIntentDraftPreflightService.PreflightResult(
                "ROUTE_RISK", "risk", java.util.List.of(risk), java.util.List.of());
        when(drafts.confirmStandaloneRoute(prepared.id(), prepared.revision()))
                .thenReturn(CalendarIntentDraftService.ConfirmationResult.completed(saved, preflight));

        IntentResult result = service.createRouteWithPreflight(command);

        assertThat(result.message())
                .contains(
                        "⚠️ 您的規劃與 1 個既有行程有衝突風險",
                        "💥 衝突行程：",
                        "🏷️ 去捷運台北車站（桃園機場捷運）",
                        "🕒 09:26",
                        "📍 捷運台北車站（桃園機場捷運）",
                        "去捷運台北車站（桃園機場捷運）」排在 09:26",
                        "本次行程 09:30 出發，中間只有 4 分鐘",
                        "兩地移動約需 36 分鐘",
                        "至少不足 32 分鐘",
                        "建議把本次行程往後安排",
                        "1. 往後安排到安全時間",
                        "2. 照原安排保留")
                .doesNotContain("nodeId", "aaaaaaaa", "bbbbbbbb");
    }

    @Test
    void standaloneWithoutAdjacentRiskSkipsGeneralBufferAndOffersDepartureReminder() {
        String source = "幫我規劃明天早上九點從捷運大坪林到捷運新店站";
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY, "去捷運新店站", null,
                "2026-08-05T09:00:00+08:00", null, "捷運新店站",
                null, null, null, null, null, null, null,
                IntentOptions.empty().withDepartureOrigin("捷運大坪林", null), source);
        var pending = pointView(CalendarIntentDraftService.Status.PENDING,
                CalendarIntentDraftService.TransportOfferStatus.NONE, 1);
        var prepared = standaloneView(CalendarIntentDraftService.Status.PENDING, 2);
        var saved = standaloneView(CalendarIntentDraftService.Status.MATERIALIZED, 3);
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        when(preferences.current()).thenReturn(Optional.empty());
        service.setRouteOperationPreferences(preferences);
        when(drafts.propose(command)).thenReturn(pending);
        var planner = mock(com.aproject.aidriven.mymobilesecretary.planner.application
                .ProviderNeutralRouteService.class);
        service.setRoutePlanner(planner);
        var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningResult.RouteOption(
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningResult.Provider.TDX,
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.TRANSIT,
                        Instant.parse("2026-08-05T01:00:00Z"),
                        Instant.parse("2026-08-05T01:08:00Z"), Duration.ofMinutes(8),
                        Duration.ZERO, null, false, true,
                        Instant.parse("2026-08-04T04:00:00Z"), java.util.List.of());
        when(planner.plan(any())).thenReturn(
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.available(java.util.List.of(option), java.util.List.of()));
        when(drafts.prepareStandaloneRoute(
                pending.id(), pending.revision(),
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TravelMode.TRANSIT,
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TimeRole.DEPART_AT,
                option)).thenReturn(prepared);
        when(drafts.confirmStandaloneRoute(prepared.id(), prepared.revision()))
                .thenReturn(CalendarIntentDraftService.ConfirmationResult.completed(saved));

        IntentResult result = service.createRouteWithPreflight(command);

        assertThat(result.nextQuestion().code()).isEqualTo("route.departure-reminder");
        assertThat(result.message())
                .contains("已替您安排", "要依照行程長度設定出發提醒嗎")
                .doesNotContain("前面與後面通常各要預留幾分鐘", "前10、後15");
        verify(planner).plan(any());
        verify(preferences, org.mockito.Mockito.never())
                .setGeneral(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void decliningTransportLeavesActivityAsCreated() {
        var offered = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.OFFERED, null, 3);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(offered));
        when(drafts.answerTransportOffer(offered.id(), offered.revision(), false))
                .thenReturn(view(CalendarIntentDraftService.Status.MATERIALIZED,
                        CalendarIntentDraftService.TransportOfferStatus.DECLINED, null, 4));

        IntentResult result = service.answerTransportPlanning("先不用").orElseThrow();

        assertThat(result.message()).contains("目的活動保留原樣", "不另外規劃交通");
        verify(drafts).answerTransportOffer(offered.id(), offered.revision(), false);
    }

    @Test
    void acceptingTransportAsksOnlyForUnknownActivityControl() {
        var offered = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.OFFERED, null, 3);
        var accepted = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.ACCEPTED, null, 4);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(offered));
        when(drafts.answerTransportOffer(offered.id(), offered.revision(), true))
                .thenReturn(accepted);

        IntentResult result = service.answerTransportPlanning("好").orElseThrow();

        assertThat(result.nextQuestion().code()).isEqualTo("route.activity-adjustability");
        assertThat(result.message())
                .contains(
                        "請選擇活動時間的調整方式",
                        "1. 活動時間固定",
                        "2. 可在指定時段內調整",
                        "3. 可配合交通調整");
    }

    @Test
    void externalControlLocksActivityAndStillAsksOneTransportModeQuestion() {
        var accepted = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.ACCEPTED, null, 4);
        var locked = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.ACCEPTED, "LOCKED", 5);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(accepted));
        when(drafts.setActivityAdjustability(
                accepted.id(), accepted.revision(), Adjustability.LOCKED)).thenReturn(locked);

        IntentResult result = service.answerTransportPlanning("時間固定，由主辦單位決定")
                .orElseThrow();

        assertThat(result.message()).contains("交通變動不得修改活動");
        assertThat(result.nextQuestion().code()).isEqualTo("route.transport-mode");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void unrelatedReadOnlyInterjectionDoesNotConsumeTransportOffer() {
        var offered = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.OFFERED, null, 3);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(offered));

        assertThat(service.answerTransportPlanning("明天有什麼行程")).isEmpty();
        verify(drafts).currentTransportConversation();
        verifyNoMoreInteractions(drafts);
    }

    @Test
    void activeRouteFocusPreventsAReplyFromConsumingAnotherTransportDraft() {
        UUID focusedId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        var focused = providerStateView(
                focusedId, CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE, 7);
        var retained = providerStateView(
                focusedId, CalendarIntentDraftService.RouteProviderStatus.RETAINED, 8);
        ConversationFocus focus = mock(ConversationFocus.class);
        when(focus.getRootDomain()).thenReturn("CALENDAR_DRAFT");
        when(focus.getWorkflowId()).thenReturn(focusedId);
        when(focus.getActivityCode()).thenReturn("revision:7");
        when(focus.getSafeLabel()).thenReturn("大坪林到台北車站");
        when(focuses.activeFocus()).thenReturn(Optional.of(focus));
        when(drafts.isAvailableForFocus(focusedId)).thenReturn(true);
        when(drafts.get(focusedId)).thenReturn(focused);
        when(drafts.retainUnavailableRoute(focusedId, 7)).thenReturn(retained);

        IntentResult result = service.answerTransportPlanning("好，保留").orElseThrow();

        assertThat(result.message()).contains("已保留這份待確認安排", "目前尚未建立行程");
        verify(drafts).retainUnavailableRoute(focusedId, 7);
        verify(drafts, org.mockito.Mockito.never()).currentTransportConversation();
    }

    @Test
    void selectedModeAddsOneTransportNodeWithoutMovingActivity() {
        var accepted = view(CalendarIntentDraftService.Status.MATERIALIZED,
                CalendarIntentDraftService.TransportOfferStatus.ACCEPTED, "LOCKED", 5);
        var planner = mock(com.aproject.aidriven.mymobilesecretary.planner.application
                .ProviderNeutralRouteService.class);
        service.setRoutePlanner(planner);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(accepted));
        var option = new com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningResult.RouteOption(
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningResult.Provider.GOOGLE,
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.TRANSIT,
                        Instant.parse("2026-08-04T00:20:00Z"),
                        Instant.parse("2026-08-04T01:00:00Z"), Duration.ofMinutes(40),
                        Duration.ZERO, null, false, true,
                        Instant.parse("2026-08-03T00:00:00Z"));
        when(planner.plan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.available(java.util.List.of(option), java.util.List.of()));
        when(drafts.materializeTransportNode(
                accepted.id(), accepted.revision(),
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TravelMode.TRANSIT,
                option)).thenReturn(view(CalendarIntentDraftService.Status.MATERIALIZED,
                        CalendarIntentDraftService.TransportOfferStatus.PLANNED, "LOCKED", 6));

        IntentResult result = service.answerTransportPlanning("搭大眾運輸").orElseThrow();

        assertThat(result.message())
                .contains(
                        "交通出發時間", "40 分鐘", "Google Maps 路線資料",
                        "Powered by Google, ©2026 Google", "活動時間沒有修改");
        verify(drafts).materializeTransportNode(
                accepted.id(), accepted.revision(),
                com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TravelMode.TRANSIT,
                option);
    }

    private static IntentCommand routeCommand() {
        return new IntentCommand(
                IntentCommand.Type.PLAN_ROUTE_ITINERARY, "參加會議", null,
                "2026-08-04T09:00:00+08:00", "2026-08-04T10:00:00+08:00",
                "桃園機場", null, null, null, null, null, null, null,
                IntentOptions.empty().withDepartureOrigin("台北車站", null),
                "明天上午九點從台北車站到桃園機場參加會議，幫我安排行程");
    }

    private static CalendarIntentDraftService.DraftView view(
            CalendarIntentDraftService.Status status,
            CalendarIntentDraftService.TransportOfferStatus offer,
            String adjustability, long revision) {
        return new CalendarIntentDraftService.DraftView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "參加會議",
                CalendarPlacement.interval(
                        Instant.parse("2026-08-04T01:00:00Z"),
                        Instant.parse("2026-08-04T02:00:00Z"), ZoneId.of("Asia/Taipei")),
                null,
                new CalendarLocation("桃園機場", 25.0, 121.0),
                new CalendarLocation("台北車站", 25.1, 121.1),
                offer, adjustability, null,
                CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED,
                null, null, null,
                null, null, status, revision,
                status == CalendarIntentDraftService.Status.MATERIALIZED
                        ? UUID.fromString("22222222-2222-2222-2222-222222222222") : null);
    }

    private static CalendarIntentDraftService.DraftView pointView(
            CalendarIntentDraftService.Status status,
            CalendarIntentDraftService.TransportOfferStatus offer,
            long revision) {
        return new CalendarIntentDraftService.DraftView(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "大坪林到台北車站",
                CalendarPlacement.point(
                        Instant.parse("2026-08-05T01:00:00Z"), ZoneId.of("Asia/Taipei")),
                null,
                new CalendarLocation("桃園捷運台北車站", 25.04869, 121.51428),
                new CalendarLocation("捷運大坪林站", 24.98275, 121.54145),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                offer, null, null,
                CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED,
                null, null, null,
                null, null, status, revision,
                status == CalendarIntentDraftService.Status.MATERIALIZED
                        ? UUID.fromString("44444444-4444-4444-4444-444444444444") : null);
    }

    private static CalendarIntentDraftService.DraftView standaloneView(
            CalendarIntentDraftService.Status status, long revision) {
        return new CalendarIntentDraftService.DraftView(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "大坪林到台北車站",
                CalendarPlacement.interval(
                        Instant.parse("2026-08-05T01:00:00Z"),
                        Instant.parse("2026-08-05T01:40:00Z"), ZoneId.of("Asia/Taipei")),
                null,
                new CalendarLocation("捷運台北車站（桃園機場捷運）", 25.04869, 121.51428),
                new CalendarLocation("捷運大坪林站", 24.98275, 121.54145),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.TransportOfferStatus.NONE,
                null,
                "TRANSIT",
                CalendarIntentDraftService.RouteProviderStatus.AVAILABLE,
                "DEPART_AT",
                "TDX",
                Instant.parse("2026-08-04T12:00:00Z"),
                null,
                null,
                status,
                revision,
                status == CalendarIntentDraftService.Status.MATERIALIZED
                        ? UUID.fromString("44444444-4444-4444-4444-444444444444") : null);
    }

    private static CalendarIntentDraftService.DraftView providerStateView(
            UUID id, CalendarIntentDraftService.RouteProviderStatus providerStatus, long revision) {
        return new CalendarIntentDraftService.DraftView(
                id,
                "大坪林到台北車站",
                CalendarPlacement.point(
                        Instant.parse("2026-08-05T01:00:00Z"), ZoneId.of("Asia/Taipei")),
                null,
                new CalendarLocation("台北車站", 25.04869, 121.51428),
                new CalendarLocation("捷運大坪林站", 24.98275, 121.54145),
                CalendarIntentDraftService.TransportOfferStatus.NONE,
                null,
                "TRANSIT",
                providerStatus,
                "DEPART_AT",
                null,
                null,
                null,
                null,
                CalendarIntentDraftService.Status.PENDING,
                revision,
                null);
    }

    private static com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningResult.RouteOption
            routeOption() {
        return new com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningResult.RouteOption(
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningResult.Provider.TDX,
                        com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.TRANSIT,
                        Instant.parse("2026-08-05T01:30:00Z"),
                        Instant.parse("2026-08-05T01:38:00Z"), Duration.ofMinutes(8),
                        Duration.ZERO, null, false, true,
                        Instant.parse("2026-08-04T04:00:00Z"), java.util.List.of());
    }
}
