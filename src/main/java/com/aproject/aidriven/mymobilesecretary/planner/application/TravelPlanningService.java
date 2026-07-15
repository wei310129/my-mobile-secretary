package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 對話用的交通時間、最晚出發時間與行程銜接計算。 */
@Service
public class TravelPlanningService {
    private final LocationEventRepository locationRepository;
    private final PlaceRepository placeRepository;
    private final TravelTimeEstimator estimator;
    private final Clock clock;

    public TravelPlanningService(LocationEventRepository locationRepository,
                                 PlaceRepository placeRepository,
                                 TravelTimeEstimator estimator, Clock clock) {
        this.locationRepository = locationRepository;
        this.placeRepository = placeRepository;
        this.estimator = estimator;
        this.clock = clock;
    }

    public Optional<TravelEstimate> fromCurrentLocation(Place destination, Instant departAt) {
        return locationRepository.findTopByOrderByOccurredAtDesc().map(from -> {
            Instant depart = departAt == null ? Instant.now(clock) : departAt;
            Duration duration = estimator.estimate(from.getLatitude(), from.getLongitude(),
                    destination.getLatitude(), destination.getLongitude(), depart);
            return new TravelEstimate(duration, depart, depart.plus(duration));
        });
    }

    public Optional<TravelEstimate> betweenPlaces(Place from, Place to, Instant departAt) {
        Instant depart = departAt == null ? Instant.now(clock) : departAt;
        Duration duration = estimator.estimate(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), depart);
        return Optional.of(new TravelEstimate(duration, depart, depart.plus(duration)));
    }

    public Optional<Instant> latestDeparture(Place destination, Instant arriveAt) {
        return fromCurrentLocation(destination, arriveAt.minus(Duration.ofHours(1)))
                .map(estimate -> arriveAt.minus(estimate.duration()));
    }

    public ConnectionCheck checkConnection(ScheduleItem first, ScheduleItem second) {
        if (first.getPlaceId() == null || second.getPlaceId() == null) {
            Duration gap = Duration.between(first.getEndAt(), second.getStartAt());
            return new ConnectionCheck(!gap.isNegative(), gap, Duration.ZERO);
        }
        Place from = placeRepository.findById(first.getPlaceId()).orElseThrow();
        Place to = placeRepository.findById(second.getPlaceId()).orElseThrow();
        Duration need = estimator.estimate(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), first.getEndAt());
        Duration gap = Duration.between(first.getEndAt(), second.getStartAt());
        return new ConnectionCheck(!gap.isNegative() && need.compareTo(gap) <= 0, gap, need);
    }

    public String trafficPayload(Place destination, long baselineMinutes) {
        var from = locationRepository.findTopByOrderByOccurredAtDesc().orElseThrow();
        return "%s,%s,%d,%d".formatted(from.getLatitude(), from.getLongitude(),
                destination.getId(), baselineMinutes);
    }

    public record TravelEstimate(Duration duration, Instant departAt, Instant arriveAt) {}
    public record ConnectionCheck(boolean feasible, Duration gap, Duration travel) {}
}
