package com.aproject.aidriven.mymobilesecretary.draft.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic conversation boundary for default and latest-draft timing changes. */
@Service
public class DraftRetentionConversationService {
    private static final Pattern DAYS = Pattern.compile("(\\d{1,2})\\s*天");
    private static final Pattern DAYS_BEFORE = Pattern.compile("到期前\\s*(\\d{1,2})\\s*天");
    private static final Pattern NUMERIC_TIME = Pattern.compile(
            ChineseTimePeriod.NON_CAPTURING_REGEX
                    + "?\\s*(\\d{1,2})(?:點半|[:：點時](\\d{1,2})?分?)");
    private static final Pattern CHINESE_TIME = Pattern.compile(
            ChineseTimePeriod.NON_CAPTURING_REGEX
                    + "?\\s*([零〇一二兩三四五六七八九十]{1,3})點(半)?");

    private final DraftRetentionService retention;

    public DraftRetentionConversationService(DraftRetentionService retention) {
        this.retention = retention;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank()) return Optional.empty();
        String normalized = text.strip();
        boolean defaultScope = normalized.contains("預設") && normalized.contains("草稿");
        boolean draftScope = normalized.contains("草稿")
                && containsAny(normalized, "這份", "這個", "剛才", "個別", "目前");
        boolean pendingTimeOnly = retention.hasUnconfirmedPreference()
                && normalized.length() <= 24 && parseTime(normalized).isPresent()
                && !containsAny(normalized, "明天", "後天", "行程", "開會", "提醒我");
        if (!defaultScope && !draftScope && !pendingTimeOnly) return Optional.empty();
        try {
            beforeMutation.run();
            if (pendingTimeOnly) {
                return updated(retention.changeDefaults(null, 0, parseTime(normalized).orElseThrow()));
            }
            if (draftScope && containsAny(normalized, "改回預設", "恢復預設", "使用預設")) {
                boolean resetRetention = containsAny(normalized, "過期", "到期", "保留");
                boolean resetReminder = normalized.contains("提醒");
                if (resetRetention || resetReminder) {
                    return updated(retention.resetLatest(resetRetention, resetReminder));
                }
            }
            Integer days = parseDays(normalized).orElse(null);
            Integer daysBefore = parseDaysBefore(normalized).orElse(null);
            LocalTime time = parseTime(normalized).orElse(null);
            if (defaultScope) {
                Integer retentionDays = requestsRetentionChange(normalized)
                        ? days : null;
                return updated(retention.changeDefaults(retentionDays, daysBefore, time));
            }
            Integer retentionDays = requestsRetentionChange(normalized) && days != null
                    ? days : null;
            Integer reminderDays = normalized.contains("提醒") && time != null
                    ? (daysBefore == null ? 0 : daysBefore) : null;
            if (retentionDays != null || reminderDays != null || time != null) {
                return updated(retention.customizeLatest(
                        retentionDays, reminderDays,
                        normalized.contains("提醒") ? time : null));
            }
            return clarification("請說明要調整草稿的保留天數或提醒時間。保留期為 1 到 30 天，"
                    + "提醒最晚 23:00，且至少要在現在 5 分鐘後。 ");
        } catch (IllegalArgumentException exception) {
            return clarification("設定未套用：保留期須為 1 到 30 天；提醒必須至少在現在 5 分鐘後、"
                    + "不晚於 23:00，且早於草稿刪除時刻。");
        } catch (IllegalStateException exception) {
            return clarification("目前沒有可調整的限期草稿，或新的預設會讓個別提醒失效；請先指定草稿。 ");
        }
    }

    static Optional<LocalTime> parseTime(String text) {
        Matcher numeric = NUMERIC_TIME.matcher(text);
        if (numeric.find()) {
            int hour = Integer.parseInt(numeric.group(1));
            int minute = numeric.group(2) == null || numeric.group(2).isBlank()
                    ? (numeric.group().contains("半") ? 30 : 0)
                    : Integer.parseInt(numeric.group(2));
            return adjusted(text, hour, minute);
        }
        Matcher chinese = CHINESE_TIME.matcher(text);
        if (chinese.find()) {
            int hour = chineseNumber(chinese.group(1));
            int minute = chinese.group(2) == null ? 0 : 30;
            return adjusted(text, hour, minute);
        }
        return Optional.empty();
    }

    private static Optional<Integer> parseDays(String text) {
        Matcher matcher = DAYS.matcher(text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static Optional<Integer> parseDaysBefore(String text) {
        Matcher matcher = DAYS_BEFORE.matcher(text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static Optional<LocalTime> adjusted(String text, int hour, int minute) {
        if (hour < 0 || hour > 24 || minute < 0 || minute > 59) return Optional.empty();
        hour = ChineseTimePeriod.toTwentyFourHour(
                ChineseTimePeriod.containedPeriod(text), hour);
        return hour > 23 ? Optional.empty() : Optional.of(LocalTime.of(hour, minute));
    }

    private static int chineseNumber(String value) {
        String normalized = value.replace('兩', '二').replace('〇', '零');
        if (normalized.equals("十")) return 10;
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(normalized.charAt(0));
            int ones = ten == normalized.length() - 1 ? 0 : digit(normalized.charAt(ten + 1));
            return tens < 0 || ones < 0 ? -1 : tens * 10 + ones;
        }
        return normalized.length() == 1 ? digit(normalized.charAt(0)) : -1;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static Optional<IntentResult> updated(String message) {
        return Optional.of(IntentResult.message(
                IntentResult.Action.REMINDER_PREFERENCE_UPDATED, message));
    }

    private static Optional<IntentResult> clarification(String message) {
        return Optional.of(IntentResult.message(IntentResult.Action.CLARIFICATION_NEEDED, message));
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static boolean requestsRetentionChange(String text) {
        return containsAny(text, "保留", "保存", "過期時間", "到期時間", "過期日", "到期日");
    }
}
