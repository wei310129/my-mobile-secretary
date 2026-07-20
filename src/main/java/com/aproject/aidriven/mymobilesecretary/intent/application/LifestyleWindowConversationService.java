package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.LifestyleWindowService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 以對話保存平日／假日三餐與睡眠時間窗，不建立假的固定行程。 */
@Service
public class LifestyleWindowConversationService {

    private static final String TIME = ChineseTimePeriod.NON_CAPTURING_REGEX + "?"
            + "(?:\\d{1,2}(?::\\d{2}|點(?:半|\\d{1,2}分)?)?|[零一二三四五六七八九十兩]{1,3}點"
            + "(?:半|[零一二三四五六七八九十兩]{1,2}分)?)";
    private static final Pattern WINDOW = Pattern.compile(
            "(?<kind>早餐|午餐|晚餐|睡眠|睡覺)(?:時間)?\\s*"
                    + "(?:是|通常在|通常|大概|都在|都)?\\s*"
                    + "(?<start>" + TIME + ")\\s*(?:到|至|[-~～])\\s*"
                    + "(?<end>" + TIME + ")");

    private final LifestyleWindowService windowService;

    public LifestyleWindowConversationService(LifestyleWindowService windowService) {
        this.windowService = windowService;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String compact = compact(text);
        if (!containsAny(compact, "早餐", "午餐", "晚餐", "睡眠", "睡覺")) {
            return Optional.empty();
        }
        if (!containsAny(compact, "平日", "上班日", "假日", "週末", "每天", "設定", "通常", "習慣")) {
            return Optional.empty();
        }

        List<LifestyleWindow.DayType> dayTypes = dayTypes(compact);
        if (dayTypes.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "這組三餐／睡眠時間是平日（上班日）還是假日（週末）？兩者可以不同；確認前不會保存，也不會建立固定行程。"));
        }
        List<ParsedWindow> parsed = parse(text);
        if (parsed.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "請提供明確時間窗，例如「平日早餐 07:00–07:30、午餐 12:00–13:00、晚餐 18:30–19:30、睡眠 23:00–07:00」。"
                            + "這些只會成為生活規劃限制，不會建立成行程。"));
        }

        beforeMutation.run();
        for (LifestyleWindow.DayType dayType : dayTypes) {
            for (ParsedWindow window : parsed) {
                windowService.set(dayType, window.kind(), window.start(), window.end());
            }
        }
        StringBuilder message = new StringBuilder("已保存生活時間窗（不建立固定行程）：");
        for (LifestyleWindow.DayType dayType : dayTypes) {
            message.append("\n").append(dayLabel(dayType)).append("：");
            for (ParsedWindow window : parsed) {
                message.append("\n- ").append(kindLabel(window.kind())).append("｜")
                        .append(window.start()).append("–").append(window.end());
            }
        }
        message.append("\n\n之後新行程若壓縮這些時段，我會標示受影響項目並讓你決定，不會自行拒絕或挪動其他行程。");
        return Optional.of(IntentResult.message(IntentResult.Action.PLANNING_PREFERENCE_SET,
                message.toString()));
    }

    static List<ParsedWindow> parse(String text) {
        List<ParsedWindow> values = new ArrayList<>();
        Matcher matcher = WINDOW.matcher(text == null ? "" : text);
        while (matcher.find()) {
            LifestyleWindow.Kind kind = switch (matcher.group("kind")) {
                case "早餐" -> LifestyleWindow.Kind.BREAKFAST;
                case "午餐" -> LifestyleWindow.Kind.LUNCH;
                case "晚餐" -> LifestyleWindow.Kind.DINNER;
                default -> LifestyleWindow.Kind.SLEEP;
            };
            LocalTime start = parseTime(matcher.group("start"), kind, true);
            LocalTime end = parseTime(matcher.group("end"), kind, false);
            if (start != null && end != null && !start.equals(end)) {
                values.add(new ParsedWindow(kind, start, end));
            }
        }
        return List.copyOf(values);
    }

    private static LocalTime parseTime(String token, LifestyleWindow.Kind kind, boolean start) {
        if (token == null) return null;
        String value = token.strip();
        String period = value.replaceAll("[\\d:零一二三四五六七八九十兩點半分]", "");
        String clock = value.substring(period.length());
        int hour;
        int minute = 0;
        if (clock.matches("\\d{1,2}(?::\\d{2})?")) {
            String[] parts = clock.split(":");
            hour = Integer.parseInt(parts[0]);
            if (parts.length > 1) minute = Integer.parseInt(parts[1]);
        } else {
            int point = clock.indexOf('點');
            if (point < 0) return null;
            String hourText = clock.substring(0, point);
            hour = hourText.matches("\\d{1,2}")
                    ? Integer.parseInt(hourText) : chineseNumber(hourText);
            String tail = clock.substring(point + 1);
            if (tail.equals("半")) minute = 30;
            else if (tail.endsWith("分")) {
                String minuteText = tail.substring(0, tail.length() - 1);
                minute = minuteText.matches("\\d{1,2}")
                        ? Integer.parseInt(minuteText) : chineseNumber(minuteText);
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        if (period.isBlank()) hour = inferHour(hour, kind, start);
        else hour = ChineseTimePeriod.toTwentyFourHour(period, hour);
        return hour > 23 ? null : LocalTime.of(hour, minute);
    }

    private static int inferHour(int hour, LifestyleWindow.Kind kind, boolean start) {
        return switch (kind) {
            case BREAKFAST -> hour;
            case LUNCH -> hour <= 5 ? hour + 12 : hour;
            case DINNER -> hour <= 10 ? hour + 12 : hour;
            case SLEEP -> start && hour >= 6 && hour <= 11 ? hour + 12 : hour;
        };
    }

    private static int chineseNumber(String value) {
        String normalized = value.replace('兩', '二');
        if (normalized.equals("十")) return 10;
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(normalized.charAt(ten - 1));
            int ones = ten == normalized.length() - 1 ? 0 : digit(normalized.charAt(ten + 1));
            return tens * 10 + ones;
        }
        return normalized.length() == 1 ? digit(normalized.charAt(0)) : -1;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static List<LifestyleWindow.DayType> dayTypes(String compact) {
        boolean weekday = containsAny(compact, "平日", "上班日");
        boolean holiday = containsAny(compact, "假日", "週末");
        if (compact.contains("每天")) return List.of(
                LifestyleWindow.DayType.WEEKDAY, LifestyleWindow.DayType.HOLIDAY);
        List<LifestyleWindow.DayType> result = new ArrayList<>();
        if (weekday) result.add(LifestyleWindow.DayType.WEEKDAY);
        if (holiday) result.add(LifestyleWindow.DayType.HOLIDAY);
        return List.copyOf(result);
    }

    private static String dayLabel(LifestyleWindow.DayType dayType) {
        return dayType == LifestyleWindow.DayType.WEEKDAY ? "平日／上班日" : "假日／週末";
    }

    private static String kindLabel(LifestyleWindow.Kind kind) {
        return switch (kind) {
            case BREAKFAST -> "早餐";
            case LUNCH -> "午餐";
            case DINNER -> "晚餐";
            case SLEEP -> "睡眠";
        };
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\s，。！？!?：:；;]", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    record ParsedWindow(LifestyleWindow.Kind kind, LocalTime start, LocalTime end) {
    }
}
