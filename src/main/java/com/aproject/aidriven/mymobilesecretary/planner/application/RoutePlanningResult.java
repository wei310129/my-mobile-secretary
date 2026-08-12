package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 可公開決策的 route evidence；不保存 raw provider payload/error。 */
public record RoutePlanningResult(
        Status status,
        List<RouteOption> options,
        List<ProviderFailure> providerFailures) {

    public RoutePlanningResult {
        options = List.copyOf(options == null ? List.of() : options);
        providerFailures = List.copyOf(
                providerFailures == null ? List.of() : providerFailures);
        if (status == Status.AVAILABLE && options.isEmpty()) {
            throw new IllegalArgumentException("available route result requires options");
        }
        if (status == Status.INSUFFICIENT_EVIDENCE && !options.isEmpty()) {
            throw new IllegalArgumentException("insufficient route result cannot contain options");
        }
    }

    public static RoutePlanningResult available(
            List<RouteOption> options, List<ProviderFailure> failures) {
        return new RoutePlanningResult(Status.AVAILABLE, options, failures);
    }

    public static RoutePlanningResult insufficient(List<ProviderFailure> failures) {
        return new RoutePlanningResult(Status.INSUFFICIENT_EVIDENCE, List.of(), failures);
    }

    public enum Status {
        AVAILABLE,
        INSUFFICIENT_EVIDENCE
    }

    public enum Provider {
        TDX,
        GOOGLE
    }

    public enum ProviderFailure {
        TDX_UNAVAILABLE,
        TDX_FAILED,
        GOOGLE_UNAVAILABLE,
        GOOGLE_FAILED
    }

    public record RouteOption(
            Provider provider,
            RoutePlanningRequest.TravelMode mode,
            Instant departAt,
            Instant arriveAt,
            Duration duration,
            Duration safetyBuffer,
            Long distanceMeters,
            boolean trafficAware,
            boolean scheduledTransit,
            Instant retrievedAt,
            List<TransitLeg> transitLegs) {

        public RouteOption(
                Provider provider,
                RoutePlanningRequest.TravelMode mode,
                Instant departAt,
                Instant arriveAt,
                Duration duration,
                Duration safetyBuffer,
                Long distanceMeters,
                boolean trafficAware,
                boolean scheduledTransit,
                Instant retrievedAt) {
            this(
                    provider, mode, departAt, arriveAt, duration, safetyBuffer,
                    distanceMeters, trafficAware, scheduledTransit, retrievedAt, List.of());
        }

        public RouteOption {
            if (provider == null || mode == null || departAt == null || arriveAt == null
                    || duration == null || duration.isNegative()
                    || safetyBuffer == null || safetyBuffer.isNegative()
                    || retrievedAt == null) {
                throw new IllegalArgumentException("route option fields are invalid");
            }
            if (distanceMeters != null && distanceMeters < 0) {
                throw new IllegalArgumentException("route distance must be non-negative");
            }
            transitLegs = List.copyOf(transitLegs == null ? List.of() : transitLegs);
        }
    }

    public record TransitLeg(
            String mode,
            String lineName,
            String headsign,
            String departureStop,
            String arrivalStop) {

        public TransitLeg {
            mode = safe(mode);
            lineName = safe(lineName);
            headsign = safe(headsign);
            departureStop = safe(departureStop);
            arrivalStop = safe(arrivalStop);
            if (lineName == null && departureStop == null && arrivalStop == null) {
                throw new IllegalArgumentException("transit leg has no public guidance");
            }
        }

        private static String safe(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
            return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
        }
    }
}
