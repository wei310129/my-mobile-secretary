package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

public record CalendarLocation(String label, Double latitude, Double longitude) {

    public CalendarLocation {
        if (label == null || label.isBlank() || label.strip().length() > 200) {
            throw new IllegalArgumentException("Location label must contain 1 to 200 characters");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Location coordinates must both be present or absent");
        }
        if (latitude != null
                && (!Double.isFinite(latitude)
                        || latitude < -90
                        || latitude > 90
                        || !Double.isFinite(longitude)
                        || longitude < -180
                        || longitude > 180)) {
            throw new IllegalArgumentException("Location coordinates are invalid");
        }
        label = label.strip();
    }

    boolean samePlace(CalendarLocation other) {
        if (latitude == null || other.latitude == null) {
            return label.equalsIgnoreCase(other.label);
        }
        return Double.compare(latitude, other.latitude) == 0
                && Double.compare(longitude, other.longitude) == 0;
    }

    public boolean hasCoordinates() {
        return latitude != null;
    }
}
