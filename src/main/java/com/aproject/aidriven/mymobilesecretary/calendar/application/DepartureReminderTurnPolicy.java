package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic interpretation of a route departure-reminder continuation. */
final class DepartureReminderTurnPolicy {

    private static final Pattern EXPLICIT_LEAD = Pattern.compile(
            "(?:出發前|提前|前)([0-9０-９零〇一二兩三四五六七八九十百]{1,6})分鐘");

    private DepartureReminderTurnPolicy() {}

    static Answer answer(String text) {
        String normalized = normalize(text);
        if (containsAny(normalized, "不用", "不要", "不需要", "先不用")) {
            return new Answer(Action.DECLINE, null);
        }
        Integer leadMinutes = explicitLeadMinutes(normalized);
        if (leadMinutes != null
                && containsAny(normalized, "提醒", "通知", "叫我", "跟我說")) {
            return new Answer(Action.EXPLICIT_LEAD, leadMinutes);
        }
        if (containsAny(normalized, "要", "好", "可以", "設定", "提醒")) {
            return new Answer(Action.ADAPTIVE, null);
        }
        return new Answer(Action.UNRECOGNIZED, null);
    }

    static boolean asksForCurrentDetails(String text) {
        String normalized = normalize(text);
        return normalized.matches("^(?:哪|是哪)(?:兩|2|幾)個$")
                || containsAny(
                        normalized,
                        "哪兩個提醒",
                        "哪2個提醒",
                        "是哪兩個提醒",
                        "哪些提醒",
                        "有哪些提醒",
                        "提醒是哪兩個",
                        "提醒有哪些",
                        "設了哪些提醒",
                        "設定了哪些提醒");
    }

    private static Integer explicitLeadMinutes(String normalized) {
        var matcher = EXPLICIT_LEAD.matcher(normalized);
        if (!matcher.find()) return null;
        Integer value = parseNumber(matcher.group(1));
        return value != null && value >= 1 && value <= 240 ? value : null;
    }

    private static Integer parseNumber(String text) {
        String ascii = text.chars()
                .map(value -> value >= '０' && value <= '９' ? value - '０' + '0' : value)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (ascii.matches("[0-9]+")) {
            try {
                return Integer.parseInt(ascii);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int total = 0;
        int digit = 0;
        boolean recognized = false;
        for (int index = 0; index < ascii.length(); index++) {
            char current = ascii.charAt(index);
            Integer mapped = chineseDigit(current);
            if (mapped != null) {
                digit = mapped;
                recognized = true;
                continue;
            }
            int unit = current == '十' ? 10 : current == '百' ? 100 : 0;
            if (unit == 0) return null;
            total += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
            recognized = true;
        }
        return recognized ? total + digit : null;
    }

    private static Integer chineseDigit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '兩' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\s，。！？!?：:；;]", "")
                        .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    enum Action {
        DECLINE,
        EXPLICIT_LEAD,
        ADAPTIVE,
        UNRECOGNIZED
    }

    record Answer(Action action, Integer leadMinutes) {}
}
