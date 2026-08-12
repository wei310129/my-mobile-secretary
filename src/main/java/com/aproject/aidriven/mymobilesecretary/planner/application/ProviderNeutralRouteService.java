package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.GoogleRoute;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.GoogleRouteQuery;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxProperties;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient.TravelQuery;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TimeRole;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TravelMode;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.Provider;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.ProviderFailure;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.RouteOption;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult.TransitLeg;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 場景式 provider policy；不以平均分數強迫單一 provider。 */
@Service
public class ProviderNeutralRouteService {

    private static final Logger log = LoggerFactory.getLogger(ProviderNeutralRouteService.class);

    private final TdxRoutingClient tdxClient;
    private final TdxProperties tdxProperties;
    private final GoogleRoutesClient googleClient;
    private final RouteProviderPolicyProperties policy;
    private final MeterRegistry metrics;
    private final Clock clock;

    public ProviderNeutralRouteService(
            TdxRoutingClient tdxClient,
            TdxProperties tdxProperties,
            GoogleRoutesClient googleClient,
            RouteProviderPolicyProperties policy,
            MeterRegistry metrics,
            Clock clock) {
        this.tdxClient = tdxClient;
        this.tdxProperties = tdxProperties;
        this.googleClient = googleClient;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RoutePlanningResult plan(RoutePlanningRequest request) {
        List<ProviderFailure> failures = new ArrayList<>();
        if (request.mode() == TravelMode.TRANSIT && request.bothEndpointsInTaiwan()) {
            return switch (policy.providerStrategy()) {
                case TDX_PRIMARY -> tdxThenGoogle(request, failures);
                case GOOGLE_PRIMARY -> googleThenTdx(request, failures, false);
                case GOOGLE_PRIMARY_TDX_CONFIRM -> googleThenTdx(request, failures, true);
            };
        }
        return googleOnly(request, failures);
    }

    private RoutePlanningResult tdxThenGoogle(
            RoutePlanningRequest request, List<ProviderFailure> failures) {
        List<RouteOption> tdx = tryTdx(request, failures);
        if (tdx != null) return RoutePlanningResult.available(tdx, failures);
        return googleOnly(request, failures);
    }

    private RoutePlanningResult googleThenTdx(
            RoutePlanningRequest request,
            List<ProviderFailure> failures,
            boolean confirmWithTdx) {
        List<RouteOption> google = tryGoogle(request, failures);
        if (google != null) {
            if (confirmWithTdx) tryTdx(request, failures);
            return RoutePlanningResult.available(google, failures);
        }
        List<RouteOption> tdx = tryTdx(request, failures);
        return tdx == null
                ? RoutePlanningResult.insufficient(failures)
                : RoutePlanningResult.available(tdx, failures);
    }

    private RoutePlanningResult googleOnly(
            RoutePlanningRequest request, List<ProviderFailure> failures) {
        List<RouteOption> google = tryGoogle(request, failures);
        return google == null
                ? RoutePlanningResult.insufficient(failures)
                : RoutePlanningResult.available(google, failures);
    }

    private List<RouteOption> tryTdx(
            RoutePlanningRequest request, List<ProviderFailure> failures) {
        long started = System.nanoTime();
        if (!tdxProperties.usable()) {
            failures.add(ProviderFailure.TDX_UNAVAILABLE);
            recordProvider("tdx", "unavailable", started);
            return null;
        }
        try {
            List<RouteOption> result = planWithTdx(request);
            recordProvider("tdx", "success", started);
            return result;
        } catch (RuntimeException exception) {
            log.warn("TDX route evidence failed; trying allowed fallback", exception);
            failures.add(ProviderFailure.TDX_FAILED);
            recordProvider("tdx", "failed", started);
            return null;
        }
    }

    private List<RouteOption> tryGoogle(
            RoutePlanningRequest request, List<ProviderFailure> failures) {
        long started = System.nanoTime();
        if (!googleClient.usable()) {
            failures.add(ProviderFailure.GOOGLE_UNAVAILABLE);
            recordProvider("google", "unavailable", started);
            return null;
        }
        try {
            List<RouteOption> result = planWithGoogle(request);
            recordProvider("google", "success", started);
            return result;
        } catch (RuntimeException exception) {
            log.warn("Google route evidence failed", exception);
            failures.add(ProviderFailure.GOOGLE_FAILED);
            recordProvider("google", "failed", started);
            return null;
        }
    }

    private void recordProvider(String provider, String outcome, long startedNanos) {
        metrics.timer(
                        "mms.route.provider.latency",
                        "provider",
                        provider,
                        "outcome",
                        outcome,
                        "strategy",
                        policy.providerStrategy().name().toLowerCase(java.util.Locale.ROOT))
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private List<RouteOption> planWithTdx(RoutePlanningRequest request) {
        Function<Instant, List<Candidate>> query = departAt -> {
            var route = tdxClient.getTransitRoute(TravelQuery.of(
                    request.fromLatitude(), request.fromLongitude(),
                    request.toLatitude(), request.toLongitude(), departAt));
            List<TransitLeg> legs = route.transitLegs().stream()
                    .map(leg -> new TransitLeg(
                            leg.mode(), leg.lineName(), leg.headsign(),
                            leg.departureStop(), leg.arrivalStop()))
                    .toList();
            return List.of(new Candidate(route.duration(), null, false, true, legs));
        };
        return materialize(request, Provider.TDX, query);
    }

    private List<RouteOption> planWithGoogle(RoutePlanningRequest request) {
        Function<Instant, List<Candidate>> query = departAt -> googleClient.computeRoutes(
                        new GoogleRouteQuery(
                                request.fromLatitude(), request.fromLongitude(),
                                request.toLatitude(), request.toLongitude(),
                                googleMode(request.mode()),
                                GoogleRoutesClient.TimeRole.DEPART_AT,
                                departAt))
                .stream()
                .map(route -> googleCandidate(route, request.mode()))
                .toList();
        if (request.timeRole() == TimeRole.ARRIVE_BY
                && request.mode() == TravelMode.TRANSIT) {
            List<GoogleRoute> routes = googleClient.computeRoutes(new GoogleRouteQuery(
                    request.fromLatitude(), request.fromLongitude(),
                    request.toLatitude(), request.toLongitude(),
                    GoogleRoutesClient.TravelMode.TRANSIT,
                    GoogleRoutesClient.TimeRole.ARRIVE_BY,
                    request.time()));
            return directArriveBy(request, Provider.GOOGLE, routes.stream()
                    .map(route -> googleCandidate(route, request.mode()))
                    .toList());
        }
        return materialize(request, Provider.GOOGLE, query);
    }

    private List<RouteOption> materialize(
            RoutePlanningRequest request,
            Provider provider,
            Function<Instant, List<Candidate>> query) {
        if (request.timeRole() == TimeRole.DEPART_AT) {
            return directDepartAt(request, provider, query.apply(request.time()));
        }
        Instant candidateDeparture = request.time()
                .minus(request.safetyBuffer())
                .minus(Duration.ofHours(1));
        List<Candidate> candidates = List.of();
        for (int attempt = 0; attempt < 4; attempt++) {
            candidates = query.apply(candidateDeparture);
            if (candidates.isEmpty()) {
                throw new IllegalStateException("route provider returned no candidate");
            }
            Instant next = request.time()
                    .minus(request.safetyBuffer())
                    .minus(candidates.get(0).duration());
            if (Duration.between(candidateDeparture, next).abs()
                    .compareTo(Duration.ofMinutes(1)) <= 0) {
                candidateDeparture = next;
                break;
            }
            candidateDeparture = next;
        }
        return routeOptions(
                request, provider, candidateDeparture, candidates, request.time());
    }

    private List<RouteOption> directDepartAt(
            RoutePlanningRequest request, Provider provider, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("route provider returned no candidate");
        }
        return routeOptions(
                request, provider, request.time(), candidates,
                request.time().plus(candidates.get(0).duration()));
    }

    private List<RouteOption> directArriveBy(
            RoutePlanningRequest request, Provider provider, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("route provider returned no candidate");
        }
        Instant departure = request.time()
                .minus(request.safetyBuffer())
                .minus(candidates.get(0).duration());
        return routeOptions(request, provider, departure, candidates, request.time());
    }

    private List<RouteOption> routeOptions(
            RoutePlanningRequest request,
            Provider provider,
            Instant primaryDeparture,
            List<Candidate> candidates,
            Instant requestedArrival) {
        Instant retrievedAt = Instant.now(clock);
        List<RouteOption> options = new ArrayList<>();
        for (int index = 0; index < Math.min(candidates.size(), 3); index++) {
            Candidate candidate = candidates.get(index);
            Instant departAt = request.timeRole() == TimeRole.DEPART_AT || index == 0
                    ? primaryDeparture
                    : requestedArrival.minus(request.safetyBuffer()).minus(candidate.duration());
            options.add(new RouteOption(
                    provider, request.mode(), departAt, departAt.plus(candidate.duration()),
                    candidate.duration(), request.safetyBuffer(), candidate.distanceMeters(),
                    candidate.trafficAware(), candidate.scheduledTransit(), retrievedAt,
                    candidate.transitLegs()));
        }
        return List.copyOf(options);
    }

    private static Candidate googleCandidate(GoogleRoute route, TravelMode mode) {
        return new Candidate(
                route.duration(), route.distanceMeters(),
                mode == TravelMode.DRIVE
                        || mode == TravelMode.RIDE_HAIL
                        || mode == TravelMode.TWO_WHEELER,
                mode == TravelMode.TRANSIT,
                route.transitLegs().stream()
                        .map(leg -> new TransitLeg(
                                "TRANSIT", leg.lineName(), leg.headsign(),
                                leg.departureStop(), leg.arrivalStop()))
                        .toList());
    }

    private static GoogleRoutesClient.TravelMode googleMode(TravelMode mode) {
        return switch (mode) {
            case DRIVE -> GoogleRoutesClient.TravelMode.DRIVE;
            case RIDE_HAIL -> GoogleRoutesClient.TravelMode.DRIVE;
            case TWO_WHEELER -> GoogleRoutesClient.TravelMode.TWO_WHEELER;
            case WALK -> GoogleRoutesClient.TravelMode.WALK;
            case TRANSIT -> GoogleRoutesClient.TravelMode.TRANSIT;
        };
    }

    private record Candidate(
            Duration duration,
            Long distanceMeters,
            boolean trafficAware,
            boolean scheduledTransit,
            List<TransitLeg> transitLegs) {
    }
}
