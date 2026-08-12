package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarBusyInterval;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjection;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjectionService;
import com.aproject.aidriven.mymobilesecretary.geo.application.ActorLocationPreferenceService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Java-owned precedence and away-from-home guard for omitted route origins. */
@Service
public class RouteOriginContextService {

    static final Duration MAX_PREVIOUS_GAP = Duration.ofHours(6);
    static final Duration OVERNIGHT_EVIDENCE_WINDOW = Duration.ofHours(18);
    static final double MIN_AWAY_DISTANCE_KILOMETERS = 30.0;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ActorLocationPreferenceService preferences;
    private final PersonalRouteProjectionService projection;

    public RouteOriginContextService(
            ActorLocationPreferenceService preferences,
            PersonalRouteProjectionService projection) {
        this.preferences = preferences;
        this.projection = projection;
    }

    public RouteOriginContextDecision resolve(Instant departure) {
        PersonalRouteProjection current = projection.current();
        List<PersonalRouteConstraint> previous = previousLocatedNodes(
                departure, current.routeConstraints());
        if (departure != null && !previous.isEmpty()) {
            PersonalRouteConstraint nearest = previous.getFirst();
            Duration gap = Duration.between(nearest.effectiveTime(), departure);
            if (!gap.isNegative() && gap.compareTo(MAX_PREVIOUS_GAP) <= 0) {
                return new RouteOriginContextDecision(
                        RouteOriginContextDecision.Kind.NEARBY_PREVIOUS,
                        nearest.location());
            }
        }
        Optional<ActorLocationPreferenceService.HomeLocation> home = preferences.home();
        if (home.isEmpty()) {
            return new RouteOriginContextDecision(
                    RouteOriginContextDecision.Kind.NEED_HOME, null);
        }
        ActorLocationPreferenceService.HomeLocation savedHome = home.orElseThrow();
        if (departure != null
                && departure.atZone(TAIPEI).getHour() < 10
                && (previous.stream().anyMatch(node -> isPlausiblyAway(node, departure, savedHome))
                        || current.busyIntervals().stream()
                                .anyMatch(interval -> crossesPreviousNight(interval, departure)))) {
            return new RouteOriginContextDecision(
                    RouteOriginContextDecision.Kind.POSSIBLY_AWAY, null);
        }
        return new RouteOriginContextDecision(
                RouteOriginContextDecision.Kind.HOME,
                new CalendarLocation(
                        savedHome.label(), savedHome.latitude(), savedHome.longitude()));
    }

    private static List<PersonalRouteConstraint> previousLocatedNodes(
            Instant departure, List<PersonalRouteConstraint> constraints) {
        if (departure == null) return List.of();
        return constraints.stream()
                .filter(node -> node.location() != null)
                .filter(node -> !node.effectiveTime().isAfter(departure))
                .sorted(Comparator.comparing(PersonalRouteConstraint::effectiveTime).reversed())
                .toList();
    }

    private static boolean crossesPreviousNight(
            CalendarBusyInterval interval, Instant departure) {
        var departureDate = departure.atZone(TAIPEI).toLocalDate();
        return interval.startAt().isBefore(departure)
                && interval.endAt().isAfter(departure)
                && interval.startAt().atZone(TAIPEI).toLocalDate().isBefore(departureDate);
    }

    private static boolean isPlausiblyAway(
            PersonalRouteConstraint node,
            Instant departure,
            ActorLocationPreferenceService.HomeLocation home) {
        Duration gap = Duration.between(node.effectiveTime(), departure);
        return gap.compareTo(MAX_PREVIOUS_GAP) > 0
                && gap.compareTo(OVERNIGHT_EVIDENCE_WINDOW) <= 0
                && kilometers(
                                node.location().latitude(),
                                node.location().longitude(),
                                home.latitude(),
                                home.longitude())
                        >= MIN_AWAY_DISTANCE_KILOMETERS;
    }

    static double kilometers(
            double firstLatitude,
            double firstLongitude,
            double secondLatitude,
            double secondLongitude) {
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double first = Math.toRadians(firstLatitude);
        double second = Math.toRadians(secondLatitude);
        double haversine = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(first)
                        * Math.cos(second)
                        * Math.sin(longitudeDelta / 2)
                        * Math.sin(longitudeDelta / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}
