package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically grounds one explicit route time without relying on model confidence. */
final class ExplicitRouteTimePolicy {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String NUMBER = "[零〇一二三四五六七八九十兩\\d]{1,3}";
    private static final Pattern TIME = Pattern.compile(
            "(?<period>" + ChineseTimePeriod.NON_CAPTURING_REGEX + ")?"
                    + "(?<hour>" + NUMBER + ")"
                    + "(?:(?:點|時)(?:(?<half>半)|(?<minute>" + NUMBER + ")分?)?"
                    + "|:(?<colon>[0-5]\\d))");

    private ExplicitRouteTimePolicy() {
    }

    static Optional<String> startAt(String text, Clock clock) {
        String normalized = text == null
                ? ""
                : Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        Matcher matcher = TIME.matcher(normalized);
        if (!matcher.find()) return Optional.empty();
        Integer hour = number(matcher.group("hour"));
        Integer minute = matcher.group("half") != null
                ? 30
                : matcher.group("colon") != null
                        ? Integer.valueOf(matcher.group("colon"))
                        : matcher.group("minute") == null ? 0 : number(matcher.group("minute"));
        if (hour == null || minute == null) return Optional.empty();
        int parsedHour = hour;
        String period = matcher.group("period");
        hour = ChineseTimePeriod.toTwentyFourHour(period, parsedHour);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return Optional.empty();

        Optional<LocalDate> date = IntentService.relativeScheduleDate(normalized, clock);
        if (date.isPresent()) {
            return Optional.of(date.orElseThrow()
                    .atTime(LocalTime.of(hour, minute))
                    .atZone(TAIPEI)
                    .toOffsetDateTime()
                    .toString());
        }
        if (!hasImmediateFutureMarker(normalized)) return Optional.empty();

        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TAIPEI));
        ZonedDateTime candidate = now.toLocalDate()
                .atTime(LocalTime.of(hour, minute))
                .atZone(TAIPEI)
                .withSecond(0)
                .withNano(0);
        if (period == null && parsedHour >= 1 && parsedHour <= 12) {
            while (!candidate.isAfter(now)) {
                candidate = candidate.plusHours(12);
            }
        } else if (!candidate.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(candidate.toOffsetDateTime().toString());
    }

    private static boolean hasImmediateFutureMarker(String normalized) {
        return normalized.contains("待會")
                || normalized.contains("待会")
                || normalized.contains("稍後")
                || normalized.contains("稍后")
                || normalized.contains("等一下")
                || normalized.contains("晚點")
                || normalized.contains("晚点");
    }

    private static Integer number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.chars().allMatch(Character::isDigit)) return Integer.valueOf(raw);
        String value = raw.replace('兩', '二').replace('〇', '零');
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.charAt(ten - 1));
            int units = ten == value.length() - 1 ? 0 : digit(value.charAt(ten + 1));
            return tens < 0 || units < 0 ? null : tens * 10 + units;
        }
        return value.length() == 1 ? digit(value.charAt(0)) : null;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }
}
