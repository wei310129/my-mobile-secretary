package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

public record CalendarLocation(String label, double latitude, double longitude) {

    public CalendarLocation {
        if (label == null || label.isBlank() || label.strip().length() > 200) {
            throw new IllegalArgumentException("Location label must contain 1 to 200 characters");
        }
        if (!Double.isFinite(latitude)
                || latitude < -90
                || latitude > 90
                || !Double.isFinite(longitude)
                || longitude < -180
                || longitude > 180) {
            throw new IllegalArgumentException("Location coordinates are invalid");
        }
        label = label.strip();
    }

    boolean samePlace(CalendarLocation other) {
        return Double.compare(latitude, other.latitude) == 0
                && Double.compare(longitude, other.longitude) == 0;
    }
}
