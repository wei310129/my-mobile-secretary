package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

record CalendarOnlineLink(String normalizedUri, String safeHost, String label) {

    static CalendarOnlineLink parse(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI input = new URI(raw.strip());
            if (!"https".equalsIgnoreCase(input.getScheme())
                    || input.getHost() == null
                    || input.getUserInfo() != null
                    || input.getFragment() != null) {
                throw invalid();
            }
            String host = input.getHost().toLowerCase(Locale.ROOT);
            int port = input.getPort() == 443 ? -1 : input.getPort();
            URI normalized = new URI(
                            "https",
                            null,
                            host,
                            port,
                            input.getPath(),
                            input.getQuery(),
                            null)
                    .normalize();
            return new CalendarOnlineLink(
                    normalized.toASCIIString(),
                    host,
                    CalendarTextNormalizer.optionalLabel(label));
        } catch (URISyntaxException invalid) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(
                "INVALID_CALENDAR_ONLINE_LINK", "Online access link must be a safe HTTPS URL");
    }
}
