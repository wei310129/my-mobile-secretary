package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Calendar placement from typed values and the exact source clause.
 *
 * <p>The model may normalize a timestamp, but it cannot invent an interval. A source clause with
 * one clock time is a point. An interval requires an explicit duration or an explicit range.
 */
public final class CalendarIntentPlacementResolver {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern DURATION = Pattern.compile(
            "(?<amount>半|[零〇一二三四五六七八九十兩\\d]{1,4})\\s*"
                    + "(?<unit>小時|鐘頭|分鐘|分)(?!制)");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:\\d{1,2}|[零〇一二三四五六七八九十兩]{1,3})"
                    + "(?:[:：]\\d{1,2}|點(?:半|\\d{1,2}分?)?)?"
                    + ".{0,10}(?:到|至|~|～|－|—|-).{0,10}"
                    + "(?:\\d{1,2}|[零〇一二三四五六七八九十兩]{1,3})"
                    + "(?:[:：]\\d{1,2}|點(?:半|\\d{1,2}分?)?)?");
    private static final Pattern EXPLICIT_END = Pattern.compile(
            "(?:結束|下課|離開|到家).{0,8}"
                    + "(?:\\d{1,2}|[零〇一二三四五六七八九十兩]{1,3})"
                    + "(?:[:：]\\d{1,2}|點(?:半|\\d{1,2}分?)?)?");
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1),
            Map.entry('二', 2), Map.entry('兩', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    private CalendarIntentPlacementResolver() {
    }

    public static CalendarPlacement resolve(IntentCommand command) {
        Instant start = parse(command.startAt());
        if (start == null) {
            throw new IllegalArgumentException("schedule missing startAt");
        }
        Instant suppliedEnd = parse(command.endAt());
        String source = command.sourceText();
        if (source == null || source.isBlank()) {
            if (suppliedEnd == null) {
                throw new IllegalArgumentException("schedule missing endAt");
            }
            return CalendarPlacement.interval(start, suppliedEnd, TAIPEI);
        }

        Duration duration = explicitDuration(source);
        if (duration != null) {
            return CalendarPlacement.interval(start, start.plus(duration), TAIPEI);
        }
        if (TIME_RANGE.matcher(source).find() || EXPLICIT_END.matcher(source).find()) {
            if (suppliedEnd == null) {
                throw new IllegalArgumentException("explicit schedule interval missing endAt");
            }
            return CalendarPlacement.interval(start, suppliedEnd, TAIPEI);
        }
        return CalendarPlacement.point(start, TAIPEI);
    }

    private static Duration explicitDuration(String source) {
        Matcher matcher = DURATION.matcher(source);
        while (matcher.find()) {
            String amount = matcher.group("amount");
            String unit = matcher.group("unit");
            int value = "半".equals(amount) ? 30 : number(amount);
            if (value <= 0) {
                continue;
            }
            long minutes = switch (unit) {
                case "小時", "鐘頭" -> "半".equals(amount) ? 30L : value * 60L;
                default -> value;
            };
            return Duration.ofMinutes(minutes);
        }
        return null;
    }

    private static int number(String raw) {
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        int ten = raw.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(raw.charAt(ten - 1));
            int ones = ten == raw.length() - 1 ? 0 : digit(raw.charAt(ten + 1));
            return tens < 0 || ones < 0 ? -1 : tens * 10 + ones;
        }
        int value = 0;
        for (int index = 0; index < raw.length(); index++) {
            int digit = digit(raw.charAt(index));
            if (digit < 0) {
                return -1;
            }
            value = value * 10 + digit;
        }
        return value;
    }

    private static int digit(char value) {
        return DIGITS.getOrDefault(value, -1);
    }

    private static Instant parse(String value) {
        return value == null || value.isBlank() ? null : ZonedDateTime.parse(value).toInstant();
    }
}
