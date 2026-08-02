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
    private final FeasibilityProperties feasibilityProperties;
    private final Clock clock;
    private ProviderNeutralRouteService providerRoutes;

    public TravelPlanningService(LocationEventRepository locationRepository,
                                 PlaceRepository placeRepository,
                                 TravelTimeEstimator estimator,
                                 FeasibilityProperties feasibilityProperties,
                                 Clock clock) {
        this.locationRepository = locationRepository;
        this.placeRepository = placeRepository;
        this.estimator = estimator;
        this.feasibilityProperties = feasibilityProperties;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProviderRoutes(ProviderNeutralRouteService providerRoutes) {
        this.providerRoutes = providerRoutes;
    }

    public Optional<TravelEstimate> fromCurrentLocation(Place destination, Instant departAt) {
        return locationRepository.findTopByOrderByOccurredAtDesc().map(from -> {
            Instant depart = departAt == null ? Instant.now(clock) : departAt;
            var evidence = estimator.estimateEvidence(from.getLatitude(), from.getLongitude(),
                    destination.getLatitude(), destination.getLongitude(), depart);
            return TravelEstimate.from(evidence, depart);
        });
    }

    public Optional<TravelEstimate> betweenPlaces(Place from, Place to, Instant departAt) {
        Instant depart = departAt == null ? Instant.now(clock) : departAt;
        var evidence = estimator.estimateEvidence(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), depart);
        return Optional.of(TravelEstimate.from(evidence, depart));
    }

    public Optional<TravelEstimate> fromCurrentLocation(
            Place destination, Instant departAt, RoutePlanningRequest.TravelMode mode) {
        if (providerRoutes == null) {
            return Optional.empty();
        }
        return locationRepository.findTopByOrderByOccurredAtDesc().flatMap(from ->
                providerTravelEstimate(
                        from.getLatitude(), from.getLongitude(), destination,
                        departAt == null ? Instant.now(clock) : departAt, mode));
    }

    public Optional<TravelEstimate> betweenPlaces(
            Place from, Place to, Instant departAt, RoutePlanningRequest.TravelMode mode) {
        if (providerRoutes == null) {
            return Optional.empty();
        }
        return providerTravelEstimate(
                from.getLatitude(), from.getLongitude(), to,
                departAt == null ? Instant.now(clock) : departAt, mode);
    }

    private Optional<TravelEstimate> providerTravelEstimate(
            double fromLatitude,
            double fromLongitude,
            Place destination,
            Instant departAt,
            RoutePlanningRequest.TravelMode mode) {
        RoutePlanningResult result = providerRoutes.plan(new RoutePlanningRequest(
                fromLatitude, fromLongitude,
                destination.getLatitude(), destination.getLongitude(),
                mode, RoutePlanningRequest.TimeRole.DEPART_AT, departAt, Duration.ZERO));
        return result.options().stream().findFirst().map(TravelEstimate::from);
    }

    public Optional<Instant> latestDeparture(Place destination, Instant arriveAt) {
        return latestDepartureFromCurrentLocation(destination, arriveAt, Duration.ZERO)
                .map(DeparturePlan::departAt);
    }

    public Optional<DeparturePlan> latestDepartureFromCurrentLocation(
            Place destination, Instant arriveAt, Duration extraArrivalBuffer) {
        return locationRepository.findTopByOrderByOccurredAtDesc()
                .flatMap(from -> departurePlan(
                        from.getLatitude(), from.getLongitude(), destination,
                        arriveAt, extraArrivalBuffer));
    }

    public Optional<DeparturePlan> latestDepartureBetweenPlaces(
            Place origin, Place destination, Instant arriveAt, Duration extraArrivalBuffer) {
        return departurePlan(origin.getLatitude(), origin.getLongitude(), destination,
                arriveAt, extraArrivalBuffer);
    }

    public Optional<DeparturePlan> latestDepartureFromCurrentLocation(
            Place destination,
            Instant arriveAt,
            Duration extraArrivalBuffer,
            RoutePlanningRequest.TravelMode mode) {
        if (providerRoutes == null) {
            return Optional.empty();
        }
        return locationRepository.findTopByOrderByOccurredAtDesc().flatMap(from ->
                providerDeparturePlan(
                        from.getLatitude(), from.getLongitude(), destination,
                        arriveAt, extraArrivalBuffer, mode));
    }

    public Optional<DeparturePlan> latestDepartureBetweenPlaces(
            Place origin,
            Place destination,
            Instant arriveAt,
            Duration extraArrivalBuffer,
            RoutePlanningRequest.TravelMode mode) {
        if (providerRoutes == null) {
            return Optional.empty();
        }
        return providerDeparturePlan(
                origin.getLatitude(), origin.getLongitude(), destination,
                arriveAt, extraArrivalBuffer, mode);
    }

    private Optional<DeparturePlan> providerDeparturePlan(
            double fromLatitude,
            double fromLongitude,
            Place destination,
            Instant arriveAt,
            Duration extraArrivalBuffer,
            RoutePlanningRequest.TravelMode mode) {
        Duration extra = extraArrivalBuffer == null ? Duration.ZERO : extraArrivalBuffer;
        if (extra.isNegative() || extra.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalArgumentException(
                    "extra arrival buffer must be between 0 and 240 minutes");
        }
        Duration transfer = feasibilityProperties.transferBuffer();
        RoutePlanningResult result = providerRoutes.plan(new RoutePlanningRequest(
                fromLatitude, fromLongitude,
                destination.getLatitude(), destination.getLongitude(),
                mode, RoutePlanningRequest.TimeRole.ARRIVE_BY, arriveAt,
                transfer.plus(extra)));
        return result.options().stream().findFirst().map(option -> new DeparturePlan(
                option.departAt(), arriveAt, option.duration(), transfer, extra,
                evidenceSource(option.provider())));
    }

    private Optional<DeparturePlan> departurePlan(
            double fromLatitude, double fromLongitude, Place destination,
            Instant arriveAt, Duration extraArrivalBuffer) {
        Duration extra = extraArrivalBuffer == null ? Duration.ZERO : extraArrivalBuffer;
        if (extra.isNegative() || extra.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalArgumentException(
                    "extra arrival buffer must be between 0 and 240 minutes");
        }
        Instant arrivalAfterParking = arriveAt.minus(extra);
        var evidence = estimator.estimateEvidence(
                fromLatitude, fromLongitude,
                destination.getLatitude(), destination.getLongitude(),
                arrivalAfterParking.minus(Duration.ofHours(1)));
        if (!evidence.supportsFeasibilityClaim()) {
            return Optional.empty();
        }
        Instant candidateDeparture = arrivalAfterParking.minus(evidence.duration());
        for (int attempt = 0; attempt < 4; attempt++) {
            var refined = estimator.estimateEvidence(
                    fromLatitude, fromLongitude,
                    destination.getLatitude(), destination.getLongitude(), candidateDeparture);
            if (!refined.supportsFeasibilityClaim()) {
                return Optional.empty();
            }
            Instant nextCandidate = arrivalAfterParking.minus(refined.duration());
            evidence = refined;
            if (Duration.between(candidateDeparture, nextCandidate).abs()
                    .compareTo(Duration.ofMinutes(1)) <= 0) {
                candidateDeparture = nextCandidate;
                break;
            }
            candidateDeparture = nextCandidate;
        }
        return Optional.of(new DeparturePlan(
                candidateDeparture, arriveAt, evidence.duration(),
                feasibilityProperties.transferBuffer(), extra, evidence.source()));
    }

    public ConnectionCheck checkConnection(ScheduleItem first, ScheduleItem second) {
        if (first.getPlaceId() == null || second.getPlaceId() == null) {
            Duration gap = Duration.between(first.getEndAt(), second.getStartAt());
            return new ConnectionCheck(
                    false,
                    false,
                    gap,
                    Duration.ZERO,
                    TravelTimeEstimator.EvidenceSource.UNAVAILABLE);
        }
        Place from = placeRepository.findById(first.getPlaceId()).orElseThrow();
        Place to = placeRepository.findById(second.getPlaceId()).orElseThrow();
        var evidence = estimator.estimateEvidence(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), first.getEndAt());
        Duration gap = Duration.between(first.getEndAt(), second.getStartAt());
        return new ConnectionCheck(
                evidence.supportsFeasibilityClaim()
                        && !gap.isNegative()
                        && evidence.duration().compareTo(gap) <= 0,
                evidence.supportsFeasibilityClaim(), gap, evidence.duration(), evidence.source());
    }

    public String trafficPayload(Place destination, long baselineMinutes) {
        var from = locationRepository.findTopByOrderByOccurredAtDesc().orElseThrow();
        return "%s,%s,%d,%d".formatted(from.getLatitude(), from.getLongitude(),
                destination.getId(), baselineMinutes);
    }

    public record TravelEstimate(
            Duration duration,
            Instant departAt,
            Instant arriveAt,
            TravelTimeEstimator.EvidenceQuality quality,
            TravelTimeEstimator.EvidenceSource source) {

        static TravelEstimate from(
                TravelTimeEstimator.TravelTimeEvidence evidence, Instant departAt) {
            return new TravelEstimate(
                    evidence.duration(), departAt, departAt.plus(evidence.duration()),
                    evidence.quality(), evidence.source());
        }

        static TravelEstimate from(RoutePlanningResult.RouteOption option) {
            return new TravelEstimate(
                    option.duration(), option.departAt(), option.arriveAt(),
                    TravelTimeEstimator.EvidenceQuality.ROUTED,
                    evidenceSource(option.provider()));
        }

        public boolean reliable() {
            return quality == TravelTimeEstimator.EvidenceQuality.ROUTED;
        }
    }

    public record DeparturePlan(
            Instant departAt,
            Instant arriveBy,
            Duration travelDuration,
            Duration includedTransferBuffer,
            Duration extraArrivalBuffer,
            TravelTimeEstimator.EvidenceSource source) {}

    public record ConnectionCheck(
            boolean feasible,
            boolean reliable,
            Duration gap,
            Duration travel,
            TravelTimeEstimator.EvidenceSource source) {}

    private static TravelTimeEstimator.EvidenceSource evidenceSource(
            RoutePlanningResult.Provider provider) {
        return switch (provider) {
            case TDX -> TravelTimeEstimator.EvidenceSource.TDX_TRANSIT;
            case GOOGLE -> TravelTimeEstimator.EvidenceSource.GOOGLE_ROUTES;
        };
    }
}
