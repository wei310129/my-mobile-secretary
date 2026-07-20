package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DevelopmentFeedCursorCodec {

    private static final String VERSION_PREFIX = "v2:";
    private static final Pattern PAYLOAD = Pattern.compile("v2:(0|[1-9][0-9]*)");

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
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor();
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw invalidCursor();
        }
        if (!PAYLOAD.matcher(decoded).matches()) {
            throw invalidCursor();
        }
        long id;
        try {
            id = Long.parseLong(decoded.substring(VERSION_PREFIX.length()));
        } catch (NumberFormatException invalid) {
            throw invalidCursor();
        }
        if (!encode(id).equals(cursor)) {
            throw invalidCursor();
        }
        return id;
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid development feed cursor");
    }
}
