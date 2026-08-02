package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.GoogleRoute;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.GoogleRouteQuery;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxProperties;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TimeRole;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TravelMode;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.Provider;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.ProviderFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderNeutralRouteServiceTest {

    private static final Instant TIME = Instant.parse("2026-08-02T02:00:00Z");
    @Mock private TdxRoutingClient tdxClient;
    @Mock private GoogleRoutesClient googleClient;

    @Test
    void taiwanTransitUsesTdxBeforeGoogle() {
        when(tdxClient.getTransitTravelTime(any())).thenReturn(Duration.ofMinutes(40));
        ProviderNeutralRouteService service = service(true);

        var result = service.plan(request(TravelMode.TRANSIT, TimeRole.DEPART_AT));

        assertThat(result.status()).isEqualTo(RoutePlanningResult.Status.AVAILABLE);
        assertThat(result.options()).singleElement().satisfies(option -> {
            assertThat(option.provider()).isEqualTo(Provider.TDX);
            assertThat(option.duration()).isEqualTo(Duration.ofMinutes(40));
            assertThat(option.scheduledTransit()).isTrue();
        });
        verify(googleClient, never()).computeRoutes(any());
    }

    @Test
    void tdxFailureFallsBackToGoogleTransitAndKeepsFailureClass() {
        when(tdxClient.getTransitTravelTime(any()))
                .thenThrow(new IntegrationException("tdx unavailable"));
        when(googleClient.usable()).thenReturn(true);
        when(googleClient.computeRoutes(any())).thenReturn(List.of(
                new GoogleRoute(Duration.ofMinutes(55), null, 42_000)));

        var result = service(true).plan(request(TravelMode.TRANSIT, TimeRole.DEPART_AT));

        assertThat(result.options()).singleElement()
                .extracting(RoutePlanningResult.RouteOption::provider)
                .isEqualTo(Provider.GOOGLE);
        assertThat(result.providerFailures()).containsExactly(ProviderFailure.TDX_FAILED);
    }

    @Test
    void drivingUsesGoogleAndPreservesOnlyReturnedAlternatives() {
        when(googleClient.usable()).thenReturn(true);
        when(googleClient.computeRoutes(any())).thenReturn(List.of(
                new GoogleRoute(Duration.ofMinutes(30), Duration.ofMinutes(24), 20_000),
                new GoogleRoute(Duration.ofMinutes(34), Duration.ofMinutes(25), 18_000)));

        var result = service(true).plan(request(TravelMode.DRIVE, TimeRole.DEPART_AT));

        assertThat(result.options()).hasSize(2).allSatisfy(option -> {
            assertThat(option.provider()).isEqualTo(Provider.GOOGLE);
            assertThat(option.departAt()).isEqualTo(TIME);
            assertThat(option.trafficAware()).isTrue();
        });
        verify(tdxClient, never()).getTransitTravelTime(any());
    }

    @Test
    void drivingArriveByIsIteratedInJavaWithoutInventingAlternative() {
        when(googleClient.usable()).thenReturn(true);
        when(googleClient.computeRoutes(any())).thenReturn(List.of(
                new GoogleRoute(Duration.ofMinutes(45), Duration.ofMinutes(35), 25_000)));

        var result = service(false).plan(request(TravelMode.DRIVE, TimeRole.ARRIVE_BY));

        assertThat(result.options()).singleElement().satisfies(option -> {
            assertThat(option.departAt()).isEqualTo(
                    TIME.minus(Duration.ofMinutes(15)).minus(Duration.ofMinutes(45)));
            assertThat(option.arriveAt()).isEqualTo(TIME.minus(Duration.ofMinutes(15)));
            assertThat(option.safetyBuffer()).isEqualTo(Duration.ofMinutes(15));
        });
        var captor = org.mockito.ArgumentCaptor.forClass(GoogleRouteQuery.class);
        verify(googleClient, org.mockito.Mockito.atLeast(2)).computeRoutes(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(query ->
                assertThat(query.timeRole())
                        .isEqualTo(GoogleRoutesClient.TimeRole.DEPART_AT));
    }

    @Test
    void unavailableProvidersReturnInsufficientEvidenceWithoutApproximation() {
        when(googleClient.usable()).thenReturn(false);

        var result = service(false).plan(request(TravelMode.TRANSIT, TimeRole.DEPART_AT));

        assertThat(result.status())
                .isEqualTo(RoutePlanningResult.Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.options()).isEmpty();
        assertThat(result.providerFailures()).containsExactly(
                ProviderFailure.TDX_UNAVAILABLE,
                ProviderFailure.GOOGLE_UNAVAILABLE);
    }

    private ProviderNeutralRouteService service(boolean tdxUsable) {
        return new ProviderNeutralRouteService(
                tdxClient,
                new TdxProperties(
                        tdxUsable, "http://tdx", "http://tdx/token",
                        tdxUsable ? "client" : "", tdxUsable ? "secret" : "",
                        Duration.ofSeconds(1)),
                googleClient,
                Clock.fixed(TIME, ZoneOffset.UTC));
    }

    private static RoutePlanningRequest request(TravelMode mode, TimeRole timeRole) {
        return new RoutePlanningRequest(
                25.0330, 121.5654, 24.9828, 121.5428,
                mode, timeRole, TIME, Duration.ofMinutes(15));
    }
}
