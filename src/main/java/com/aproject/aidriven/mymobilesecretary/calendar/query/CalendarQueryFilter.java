package com.aproject.aidriven.mymobilesecretary.calendar.query;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public record CalendarQueryFilter(
        Instant fromInclusive,
        Instant toExclusive,
        ZoneId zoneId,
        String keyword,
        String category,
        int limit,
        int offset) {

    public CalendarQueryFilter {
        if ((fromInclusive == null) != (toExclusive == null)) {
            throw new IllegalArgumentException("Calendar range requires both boundaries");
        }
        if (fromInclusive != null && !toExclusive.isAfter(fromInclusive)) {
            throw new IllegalArgumentException("Calendar range end must be after start");
        }
        zoneId = Objects.requireNonNullElse(zoneId, ZoneId.of("Asia/Taipei"));
        keyword = normalize(keyword, 200, "keyword");
        category = normalize(category, 80, "category");
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Calendar query limit must be between 1 and 50");
        }
        if (offset < 0 || offset > 10_000) {
            throw new IllegalArgumentException("Calendar query offset is out of range");
        }
    }

    public static CalendarQueryFilter range(
            Instant fromInclusive, Instant toExclusive, ZoneId zoneId, int limit, int offset) {
        return new CalendarQueryFilter(
                fromInclusive, toExclusive, zoneId, null, null, limit, offset);
    }

    public static CalendarQueryFilter search(
            String keyword, String category, int limit, int offset) {
        return new CalendarQueryFilter(
                null, null, ZoneId.of("Asia/Taipei"), keyword, category, limit, offset);
    }

    private static String normalize(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("Calendar query " + label + " is too long");
        }
        return normalized;
    }
}
