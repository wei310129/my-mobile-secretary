package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;
import java.time.Instant;

/** Provider-neutral route request；time role、mode 與 safety buffer 不可互換。 */
public record RoutePlanningRequest(
        double fromLatitude,
        double fromLongitude,
        double toLatitude,
        double toLongitude,
        TravelMode mode,
        TimeRole timeRole,
        Instant time,
        Duration safetyBuffer) {

    public RoutePlanningRequest {
        if (!validLatitude(fromLatitude) || !validLatitude(toLatitude)
                || !validLongitude(fromLongitude) || !validLongitude(toLongitude)) {
            throw new IllegalArgumentException("route coordinates are invalid");
        }
        if (mode == null || timeRole == null || time == null) {
            throw new IllegalArgumentException("route mode, time role and time are required");
        }
        safetyBuffer = safetyBuffer == null ? Duration.ZERO : safetyBuffer;
        if (safetyBuffer.isNegative() || safetyBuffer.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalArgumentException("route safety buffer must be between 0 and 240 minutes");
        }
    }

    public boolean bothEndpointsInTaiwan() {
        return inTaiwan(fromLatitude, fromLongitude) && inTaiwan(toLatitude, toLongitude);
    }

    private static boolean inTaiwan(double latitude, double longitude) {
        return latitude >= 20.5 && latitude <= 26.5
                && longitude >= 118.0 && longitude <= 123.0;
    }

    private static boolean validLatitude(double latitude) {
        return latitude >= -90 && latitude <= 90;
    }

    private static boolean validLongitude(double longitude) {
        return longitude >= -180 && longitude <= 180;
    }

    public enum TravelMode {
        DRIVE,
        RIDE_HAIL,
        TWO_WHEELER,
        WALK,
        TRANSIT
    }

    public enum TimeRole {
        DEPART_AT,
        ARRIVE_BY
    }
}
