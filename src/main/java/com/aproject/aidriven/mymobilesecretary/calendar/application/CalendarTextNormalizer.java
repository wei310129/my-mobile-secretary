package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.text.Normalizer;

final class CalendarTextNormalizer {

    private CalendarTextNormalizer() {}

    static String optionalCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
        if (normalized.length() > 80) {
            throw new IllegalArgumentException("Calendar category exceeds 80 characters");
        }
        return normalized;
    }

    static String optionalLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("Online link label exceeds 120 characters");
        }
        return normalized;
    }
}
