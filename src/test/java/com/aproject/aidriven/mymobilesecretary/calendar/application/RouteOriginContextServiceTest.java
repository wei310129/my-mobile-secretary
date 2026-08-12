package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarBusyInterval;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjection;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjectionService;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.geo.application.ActorLocationPreferenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RouteOriginContextServiceTest {

    private static final Instant NINE_AM = Instant.parse("2026-08-05T01:00:00Z");

    @Mock private ActorLocationPreferenceService preferences;
    @Mock private PersonalRouteProjectionService projection;

    private RouteOriginContextService service;

    @BeforeEach
    void setUp() {
        service = new RouteOriginContextService(preferences, projection);
        org.mockito.Mockito.lenient()
                .when(projection.current())
                .thenReturn(new PersonalRouteProjection(List.of(), List.of()));
    }

    @Test
    void asksOnceWhenNoTypedHomeOrNearbyLocationExists() {
        when(preferences.home()).thenReturn(Optional.empty());

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind()).isEqualTo(RouteOriginContextDecision.Kind.NEED_HOME);
        assertThat(decision.location()).isNull();
    }

    @Test
    void knownPreviousLocationWithinSixHoursWinsOverHome() {
        CalendarLocation office = new CalendarLocation("辦公室", 25.033, 121.5654);
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(), List.of(node(NINE_AM.minusSeconds(6 * 3600), office))));

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind())
                .isEqualTo(RouteOriginContextDecision.Kind.NEARBY_PREVIOUS);
        assertThat(decision.location()).isEqualTo(office);
    }

    @Test
    void farKnownLocationFromPreviousEveningMakesEarlyMorningHomeUseFailClosed() {
        homeInTaipei();
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(),
                List.of(node(
                        NINE_AM.minusSeconds(14 * 3600),
                        new CalendarLocation("高雄住宿地點", 22.6273, 120.3014)))));

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind())
                .isEqualTo(RouteOriginContextDecision.Kind.POSSIBLY_AWAY);
        assertThat(decision.location()).isNull();
    }

    @Test
    void farPreviousEveningDoesNotOverrideHomeAtOrAfterTenAm() {
        homeInTaipei();
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(),
                List.of(node(
                        NINE_AM.minusSeconds(14 * 3600),
                        new CalendarLocation("高雄住宿地點", 22.6273, 120.3014)))));

        RouteOriginContextDecision decision = service.resolve(
                Instant.parse("2026-08-05T02:00:00Z"));

        assertThat(decision.kind()).isEqualTo(RouteOriginContextDecision.Kind.HOME);
        assertThat(decision.location().label()).isEqualTo("我家");
    }

    @Test
    void nearbyPreviousEveningDoesNotCreateTravelEvidenceFromTitleAlone() {
        homeInTaipei();
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(),
                List.of(node(
                        NINE_AM.minusSeconds(14 * 3600),
                        new CalendarLocation("某飯店", 25.034, 121.564)))));

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind()).isEqualTo(RouteOriginContextDecision.Kind.HOME);
        assertThat(decision.location().label()).isEqualTo("我家");
    }

    @Test
    void overnightActivityCoveringDeparturePreventsAutomaticHomeUse() {
        homeInTaipei();
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(new CalendarBusyInterval(
                        "跨夜活動",
                        Instant.parse("2026-08-04T14:00:00Z"),
                        Instant.parse("2026-08-05T02:00:00Z"))),
                List.of()));

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind())
                .isEqualTo(RouteOriginContextDecision.Kind.POSSIBLY_AWAY);
    }

    @Test
    void allDayDraftWithoutAwayEvidenceUsesTypedHome() {
        homeInTaipei();

        RouteOriginContextDecision decision = service.resolve(null);

        assertThat(decision.kind()).isEqualTo(RouteOriginContextDecision.Kind.HOME);
        assertThat(decision.location().label()).isEqualTo("我家");
    }

    @Test
    void confirmedReturnHomeWithinSixHoursWinsOverOlderFarEvidence() {
        CalendarLocation home = new CalendarLocation("我家", 25.033, 121.5654);
        when(projection.current()).thenReturn(new PersonalRouteProjection(
                List.of(),
                List.of(
                        node(
                                NINE_AM.minusSeconds(14 * 3600),
                                new CalendarLocation("海外抵達點", 35.6762, 139.6503)),
                        node(NINE_AM.minusSeconds(2 * 3600), home))));

        RouteOriginContextDecision decision = service.resolve(NINE_AM);

        assertThat(decision.kind())
                .isEqualTo(RouteOriginContextDecision.Kind.NEARBY_PREVIOUS);
        assertThat(decision.location()).isEqualTo(home);
    }

    @Test
    void distancePolicyUsesThirtyKilometerThreshold() {
        assertThat(RouteOriginContextService.kilometers(25.033, 121.5654, 25.20, 121.5654))
                .isLessThan(30.0);
        assertThat(RouteOriginContextService.kilometers(25.033, 121.5654, 25.40, 121.5654))
                .isGreaterThan(30.0);
    }

    private void homeInTaipei() {
        when(preferences.home()).thenReturn(Optional.of(
                new ActorLocationPreferenceService.HomeLocation(
                        1L, "我家", 25.033, 121.5654)));
    }

    private static PersonalRouteConstraint node(Instant time, CalendarLocation location) {
        return new PersonalRouteConstraint(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "arrival",
                time,
                location,
                Adjustability.LOCKED,
                1);
    }
}
