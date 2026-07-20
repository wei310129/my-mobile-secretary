package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import com.aproject.aidriven.mymobilesecretary.planner.application.FeasibilityService;
import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.planner.application.LocalizedWeatherService;
import com.aproject.aidriven.mymobilesecretary.planner.application.RouteSuggestionService;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelPlanningService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlannerIntentHandlerTest {

    private LocalizedWeatherService weatherService;
    private PlaceAliasService placeAliasService;
    private TravelPlanningService travelPlanningService;
    private FeasibilityService feasibilityService;
    private PlanningPreferenceService preferenceService;
    private PlannerIntentHandler handler;

    @BeforeEach
    void setUp() {
        weatherService = mock(LocalizedWeatherService.class);
        placeAliasService = mock(PlaceAliasService.class);
        travelPlanningService = mock(TravelPlanningService.class);
        feasibilityService = mock(FeasibilityService.class);
        preferenceService = mock(PlanningPreferenceService.class);
        handler = new PlannerIntentHandler(
                mock(TaskService.class),
                mock(ScheduleService.class),
                placeAliasService,
                mock(PlaceService.class),
                mock(FreeSlotService.class),
                feasibilityService,
                mock(RouteSuggestionService.class),
                travelPlanningService,
                weatherService,
                preferenceService,
                mock(ConversationContextService.class),
                Clock.systemUTC());
    }

    @Test
    void registersEveryPlannerWeatherAndTransportType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.SUGGEST_FREE_SLOT,
                IntentCommand.Type.SUGGEST_ROUTE_TASKS,
                IntentCommand.Type.ASK_WEATHER,
                IntentCommand.Type.CREATE_WEATHER_REMINDER,
                IntentCommand.Type.ASK_TRAVEL_TIME,
                IntentCommand.Type.ASK_DEPARTURE_TIME,
                IntentCommand.Type.CREATE_TRAFFIC_WATCH,
                IntentCommand.Type.CHECK_FEASIBILITY,
                IntentCommand.Type.SET_PLANNING_BUFFER));
    }

    @Test
    void weatherQueryDelegatesToDeterministicWeatherService() {
        when(weatherService.describeCurrentForecast()).thenReturn("台北晴天");

        IntentResult result = handler.handle("今天天氣", command(IntentCommand.Type.ASK_WEATHER));

        assertThat(result.action()).isEqualTo(IntentResult.Action.WEATHER_INFO);
        assertThat(result.message()).isEqualTo(
                IntentResult.message(IntentResult.Action.WEATHER_INFO, "台北晴天").message());
    }

    @Test
    void departureQueryUsesExplicitOriginAndSeparatesParkingBuffer() {
        Place home = Place.create("家", null, 24.98, 121.54, "HOME", Instant.EPOCH);
        Place hospital = Place.create(
                "台大醫院", null, 25.04, 121.52, "HOSPITAL", Instant.EPOCH);
        Instant arriveBy = Instant.parse("2026-07-22T02:00:00Z");
        var plan = new TravelPlanningService.DeparturePlan(
                Instant.parse("2026-07-22T00:55:00Z"), arriveBy,
                Duration.ofMinutes(50), Duration.ofMinutes(10), Duration.ofMinutes(15));
        when(placeAliasService.resolve("家")).thenReturn(Optional.of(home));
        when(placeAliasService.resolve("台大醫院")).thenReturn(Optional.of(hospital));
        when(travelPlanningService.latestDepartureBetweenPlaces(
                home, hospital, arriveBy, Duration.ofMinutes(15))).thenReturn(plan);

        IntentCommand command = new IntentCommand(
                IntentCommand.Type.ASK_DEPARTURE_TIME, null,
                "2026-07-22T10:00:00+08:00", null, null, "台大醫院",
                null, null, null, null, null, null, null,
                com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions.empty()
                        .withDepartureOrigin("家", 15));

        IntentResult result = handler.handle("十點前到醫院", command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.TRAVEL_INFO);
        assertThat(result.message()).contains(
                "從「家」最晚 07/22 08:55 出發",
                "交通估計 50 分鐘",
                "基本轉場緩衝 10 分鐘",
                "另保留 15 分鐘停車／抵達緩衝",
                "10:00 前到「台大醫院」");
    }

    @Test
    void hypotheticalFeasibilityChecksExpandedWindowWithoutCreatingSchedule() {
        Instant start = Instant.parse("2026-07-19T01:00:00Z");
        Instant end = Instant.parse("2026-07-19T02:00:00Z");
        when(feasibilityService.analyzeHypotheticalWindow(
                "會議", start, end, Duration.ofMinutes(20), Duration.ofMinutes(40)))
                .thenReturn(new FeasibilityService.HypotheticalWindowAnalysis(
                        "會議", start, end,
                        Instant.parse("2026-07-19T00:40:00Z"),
                        Instant.parse("2026-07-19T02:40:00Z"),
                        Duration.ofMinutes(20), Duration.ofMinutes(40),
                        java.util.List.of(new FeasibilityService.HypotheticalConflict(
                                FeasibilityService.HypotheticalSegment.PREPARATION,
                                "送小孩", Instant.parse("2026-07-19T00:40:00Z"),
                                Instant.parse("2026-07-19T00:50:00Z")))));
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CHECK_FEASIBILITY, "會議", null,
                "2026-07-19T09:00:00+08:00", "2026-07-19T10:00:00+08:00",
                null, null, null, null, null, null, null, null,
                com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions.empty()
                        .withHypotheticalBuffers(20, 40));

        IntentResult result = handler.handle("先不要建，幫我看會不會撞到", command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.CONNECTION_CHECKED);
        assertThat(result.message()).contains(
                "只做檢查，未建立行程", "08:40–10:40", "準備 20 分鐘",
                "後續交通 40 分鐘", "準備段與「送小孩」", "08:40–08:50");
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
