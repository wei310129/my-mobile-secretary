package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 限定日期與起訖的空檔只讀查詢；回覆選項，不建立任何活動。 */
@Service
public class BoundedFreeSlotConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String HOUR =
            "(?:\\d{1,2}|[一二三四五六七八九兩]|十(?:[一二三四五六七八九])?|二十(?:[一二三])?)";
    private static final String CLOCK =
            "(?:(?:凌晨|早上|上午|中午|下午|晚上))?" + HOUR
                    + "(?:點(?:半|[零一二三四五六七八九十兩\\d]{1,2}分?)?|:[0-5]\\d)";
    private static final Pattern RANGE = Pattern.compile(
            "明天(?<start>" + CLOCK + ")(?:到|至)(?<end>" + CLOCK + ")");
    private static final Pattern DURATION = Pattern.compile(
            "(?:連續|至少)?(?<hours>" + HOUR + ")個?小時(?:的)?(?:完整)?空檔");
    private static final Pattern LIMIT = Pattern.compile(
            "(?:給我|列出|提供)?(?<count>[一二三四五六七八九兩\\d])個(?:選項|時段)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final FreeSlotService freeSlotService;
    private final Clock clock;

    public BoundedFreeSlotConversationService(FreeSlotService freeSlotService, Clock clock) {
        this.freeSlotService = freeSlotService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (looksLikeMeta(compact) || !readOnlyIntent(compact)) return Optional.empty();
        Matcher range = RANGE.matcher(compact);
        Matcher durationMatcher = DURATION.matcher(compact);
        if (!range.find() || !durationMatcher.find()) return Optional.empty();

        String inheritedPeriod = periodOf(range.group("start"));
        LocalTime startTime = parseTime(range.group("start"), null);
        LocalTime endTime = parseTime(range.group("end"), inheritedPeriod);
        int hours = number(durationMatcher.group("hours"));
        if (startTime == null || endTime == null || !endTime.isAfter(startTime) || hours <= 0) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "空檔查詢的起訖或需要時長不完整；我沒有建立任何行程。"));
        }

        int limit = 2;
        Matcher limitMatcher = LIMIT.matcher(compact);
        if (limitMatcher.find()) limit = Math.min(5, Math.max(1, number(limitMatcher.group("count"))));
        LocalDate date = LocalDate.now(clock.withZone(TAIPEI)).plusDays(1);
        Instant from = date.atTime(startTime).atZone(TAIPEI).toInstant();
        Instant until = date.atTime(endTime).atZone(TAIPEI).toInstant();
        Duration need = Duration.ofHours(hours);
        List<FreeSlotService.Slot> slots = freeSlotService.suggest(from, until, need, null)
                .stream().limit(limit).toList();
        if (slots.isEmpty()) {
            return Optional.of(IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                    "明天 %s–%s 內沒有連續 %d 小時的完整空檔；這次只有查詢，沒有建立或修改資料。"
                            .formatted(startTime, endTime, hours)));
        }
        String choices = java.util.stream.IntStream.range(0, slots.size())
                .mapToObj(index -> "%d. %s–%s".formatted(index + 1,
                        slots.get(index).startAt().atZone(TAIPEI).format(TIME),
                        slots.get(index).endAt().atZone(TAIPEI).format(TIME)))
                .collect(Collectors.joining("\n"));
        return Optional.of(IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                "明天 %s–%s 內可用的連續 %d 小時選項：\n%s\n\n你還沒決定要排什麼，所以只提供空檔，沒有建立或修改資料。"
                        .formatted(startTime, endTime, hours, choices)));
    }

    private static boolean readOnlyIntent(String text) {
        return text.contains("空檔") && (text.contains("沒決定") || text.contains("還沒決定")
                || text.contains("先給") || text.contains("只想知道")
                || text.contains("先看") || text.contains("不要排") || text.contains("不要建立"));
    }

    private static String periodOf(String raw) {
        for (String period : List.of("凌晨", "早上", "上午", "中午", "下午", "晚上")) {
            if (raw.startsWith(period)) return period;
        }
        return null;
    }

    private static LocalTime parseTime(String raw, String defaultPeriod) {
        Matcher matcher = Pattern.compile(
                "(?:(凌晨|早上|上午|中午|下午|晚上))?(" + HOUR
                        + ")(?:點(?:(半)|([零一二三四五六七八九十兩\\d]{1,2})分?)?|:([0-5]\\d))")
                .matcher(raw);
        if (!matcher.matches()) return null;
        int hour = number(matcher.group(2));
        int minute = matcher.group(3) != null ? 30
                : matcher.group(4) != null ? number(matcher.group(4))
                : matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : 0;
        String period = matcher.group(1) == null ? defaultPeriod : matcher.group(1);
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        else if ("中午".equals(period) && hour < 11) hour += 12;
        else if ("凌晨".equals(period) && hour == 12) hour = 0;
        return hour > 23 || minute > 59 ? null : LocalTime.of(hour, minute);
    }

    private static int number(String raw) {
        if (raw.chars().allMatch(Character::isDigit)) return Integer.parseInt(raw);
        String value = raw.replace('兩', '二');
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.charAt(ten - 1));
            int units = ten == value.length() - 1 ? 0 : digit(value.charAt(ten + 1));
            return tens * 10 + units;
        }
        return digit(value.charAt(0));
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static boolean looksLikeMeta(String text) {
        return List.of("情境清單", "測試資料", "功能開發", "使用者說").stream()
                .anyMatch(text::contains);
    }
}
