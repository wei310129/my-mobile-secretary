package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.regex.Pattern;

/** Final, channel-neutral secretary tone normalization after every decorator has run. */
final class SecretaryReplyTonePolicy {

    private static final Pattern DECORATIVE_LINE_PREFIX = Pattern.compile(
            "(?m)^[\\p{So}\\p{Sk}]\\uFE0F?(?:\\u200D[\\p{So}\\p{Sk}]\\uFE0F?)*\\s*");

    private SecretaryReplyTonePolicy() {
    }

    static String normalize(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        return DECORATIVE_LINE_PREFIX.matcher(message)
                .replaceAll("")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }
}
