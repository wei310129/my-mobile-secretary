package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DevelopmentFeedCursorCodec {

    private static final String VERSION_PREFIX = "v1:";

    public String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("cursor id must not be negative");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (VERSION_PREFIX + id).getBytes(StandardCharsets.UTF_8));
    }

    public long decode(String cursor) {
        if (cursor == null) {
            return 0;
        }
        if (cursor.isBlank() || cursor.length() > 128) {
            throw invalidCursor();
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(VERSION_PREFIX)) {
                throw invalidCursor();
            }
            long id = Long.parseLong(decoded.substring(VERSION_PREFIX.length()));
            if (id < 0) {
                throw invalidCursor();
            }
            return id;
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor();
        }
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid development feed cursor");
    }
}
