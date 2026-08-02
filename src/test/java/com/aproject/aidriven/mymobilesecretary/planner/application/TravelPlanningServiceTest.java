package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TravelPlanningServiceTest {

    @Test
    void explicitOriginAndArrivalBufferAreBothAppliedToLatestDeparture() {
        TravelTimeEstimator estimator = (fromLat, fromLon, toLat, toLon, departAt) ->
                Duration.ofMinutes(50);
        FeasibilityProperties properties = new FeasibilityProperties(
                25, Duration.ofMinutes(10));
        TravelPlanningService service = new TravelPlanningService(
                mock(LocationEventRepository.class), mock(PlaceRepository.class),
                estimator, properties,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
        Place home = Place.create("家", null, 24.98, 121.54, "HOME", Instant.EPOCH);
        Place hospital = Place.create(
                "台大醫院", null, 25.04, 121.52, "HOSPITAL", Instant.EPOCH);
        Instant arriveBy = Instant.parse("2026-07-22T02:00:00Z");
        TravelPlanningService.DeparturePlan plan = service.latestDepartureBetweenPlaces(
                home, hospital, arriveBy, Duration.ofMinutes(15)).orElseThrow();

        assertThat(plan.departAt()).isEqualTo(Instant.parse("2026-07-22T00:55:00Z"));
        assertThat(plan.arriveBy()).isEqualTo(arriveBy);
        assertThat(plan.travelDuration()).isEqualTo(Duration.ofMinutes(50));
        assertThat(plan.includedTransferBuffer()).isEqualTo(Duration.ofMinutes(10));
        assertThat(plan.extraArrivalBuffer()).isEqualTo(Duration.ofMinutes(15));
        assertThat(plan.source()).isEqualTo(TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED);
    }

    @Test
    void straightLineApproximationCannotProduceLatestSafeDeparture() {
        FeasibilityProperties properties = new FeasibilityProperties(
                25, Duration.ofMinutes(10));
        TravelPlanningService service = new TravelPlanningService(
                mock(LocationEventRepository.class), mock(PlaceRepository.class),
                new StraightLineTravelTimeEstimator(properties), properties,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
        Place home = Place.create("家", null, 24.98, 121.54, "HOME", Instant.EPOCH);
        Place hospital = Place.create(
                "台大醫院", null, 25.04, 121.52, "HOSPITAL", Instant.EPOCH);

        assertThat(service.latestDepartureBetweenPlaces(
                        home, hospital, Instant.parse("2026-07-22T02:00:00Z"), Duration.ZERO))
                .isEmpty();
    }
}
