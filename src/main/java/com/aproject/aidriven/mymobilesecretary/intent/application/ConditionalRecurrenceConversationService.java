package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.application.ConditionalRecurrenceService;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceRule;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 將官方狀態相關的固定需求保存為條件規則草稿，而不是一般 WEEKLY 行程。 */
@Service
public class ConditionalRecurrenceConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern ACTIVATE = Pattern.compile(
            "(?:確認|同意)?(?:啟用|啟動)條件(?:式)?規則(?:編號)?[#＃]?\\s*(\\d+)");
    private static final Pattern WEEKDAY = Pattern.compile(
            "(?:每週|每星期|每個禮拜|週|星期|禮拜)([一二三四五六日天])");
    private static final Pattern TIME = Pattern.compile(
            ChineseTimePeriod.CAPTURING_REGEX + "?([零一二三四五六七八九十兩\\d]{1,3})"
                    + "(?:點|:)(半|[零一二三四五六七八九十兩\\d]{1,2}分?|[0-5]\\d)?");
    private static final Pattern DURATION = Pattern.compile(
            "(?:維持|持續|開|上|排)?([零一二三四五六七八九十兩\\d]{1,3})(小時|鐘頭|分鐘)");
    private static final Pattern TITLE = Pattern.compile(
            "(?:點半|點|分)(?:開始)?(?:開|上|排|做|去)?([^，,。；;]{1,40}?)(?:一小時|[零一二三四五六七八九十兩\\d]+(?:小時|鐘頭|分鐘)|做到|，|,|如果|若|國定假日|颱風)");

    private final ConditionalRecurrenceService recurrenceService;
    private final Clock clock;

    public ConditionalRecurrenceConversationService(
            ConditionalRecurrenceService recurrenceService, Clock clock) {
        this.recurrenceService = recurrenceService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        return answer(text, ConversationSnapshot.empty(), beforeMutation);
    }

    public Optional<IntentResult> answer(
            String text, ConversationSnapshot previous, Runnable beforeMutation) {
        String current = normalize(text);
        if (looksLikeMeta(current)) return Optional.empty();
        String compact = resumePendingDuration(current, previous);

        Matcher activation = ACTIVATE.matcher(compact);
        if (activation.find()) {
            beforeMutation.run();
            ConditionalRecurrenceRule rule = recurrenceService.activate(
                    Long.parseLong(activation.group(1)));
            return Optional.of(IntentResult.message(IntentResult.Action.SCHEDULE_RECURRENCE_SET,
                    "已啟用條件規則 #%d「%s」。每次都會先查核官方狀態；尚未確認時不建立或移動行程。"
                            .formatted(rule.getId(), rule.getTitle())));
        }

        if (!isConditionalRecurrence(compact)) return Optional.empty();
        Parsed parsed = parse(compact);
        List<String> missing = new ArrayList<>();
        if (!hasExplicitRecurrence(compact)) {
            missing.add("是否每週固定（名稱叫「週會」不代表一定要重複）");
        }
        if (parsed.weekday() == null) missing.add("每週星期幾");
        if (parsed.startTime() == null) missing.add("開始時間");
        if (parsed.startTime() != null && !parsed.timePeriodExplicit()) {
            missing.add("未帶時段的鐘點是上午或晚上（例如七點需明講）");
        }
        if (parsed.duration() == null) missing.add("每次持續多久");
        if (parsed.title() == null) missing.add("行程名稱");
        if (parsed.closurePolicy() != ConditionalRecurrenceRule.ClosurePolicy.NONE
                && parsed.jurisdiction() == null) {
            missing.add("停班停課適用縣市");
        }
        if (!missing.isEmpty()) {
            String supportedBoundary = parsed.holidayPolicy()
                    == ConditionalRecurrenceRule.HolidayPolicy.SKIP
                            ? "已確定國定假日採跳過，不會前移；補課不會自動建立，等老師另行提供後再處理。"
                            : "";
            return Optional.of(IntentResult.clarificationNeeded(
                    "我已辨識這是依官方假日／停班停課調整的條件式週期，不會建立成普通每週固定行程。"
                            + supportedBoundary + "還需要確認：" + String.join("、", missing)
                            + "。資訊補齊後只會先存草稿，明確啟用前不建立任何一場。"));
        }

        ZonedDateTime start = nextOccurrence(parsed.weekday(), parsed.startTime());
        Instant startAt = start.toInstant();
        Instant endAt = start.plus(parsed.duration()).toInstant();
        beforeMutation.run();
        ConditionalRecurrenceRule rule = recurrenceService.createDraft(
                parsed.title(), startAt, endAt, parsed.until(), parsed.holidayPolicy(),
                parsed.closurePolicy(), parsed.jurisdiction());
        String message = ("已建立條件式週期草稿 #%d「%s」（尚未啟用）：\n"
                        + "- 基準時間｜%s\n- 國定假日｜%s\n- 停班停課｜%s\n- 補課｜%s\n\n"
                        + "它不是普通固定行程；請核對後回覆「啟用條件規則 %d」。")
                .formatted(rule.getId(), rule.getTitle(), start,
                        holidayLabel(rule.getHolidayPolicy()),
                        closureLabel(rule), makeupLabel(rule), rule.getId());
        return Optional.of(IntentResult.message(
                IntentResult.Action.PLANNING_PREFERENCE_SET, message));
    }

    private Parsed parse(String text) {
        Matcher weekdayMatcher = WEEKDAY.matcher(text);
        boolean hasWeekday = weekdayMatcher.find();
        DayOfWeek weekday = hasWeekday ? weekday(weekdayMatcher.group(1)) : null;
        Matcher timeMatcher = TIME.matcher(text);
        if (hasWeekday) {
            timeMatcher.region(weekdayMatcher.end(), text.length());
        }
        LocalTime start = null;
        boolean timePeriodExplicit = false;
        while (timeMatcher.find()) {
            LocalTime candidate = parseTime(timeMatcher);
            boolean candidateExplicit = timeMatcher.group(1) != null
                    || Optional.ofNullable(number(timeMatcher.group(2))).orElse(0) >= 13;
            if (start == null || candidateExplicit) {
                start = candidate;
                timePeriodExplicit = candidateExplicit;
            }
            if (candidateExplicit) break;
        }
        Duration duration = parseDuration(text);
        Matcher titleMatcher = TITLE.matcher(text);
        String title = titleMatcher.find() ? cleanTitle(titleMatcher.group(1)) : null;
        ConditionalRecurrenceRule.HolidayPolicy holiday = asksHolidaySkip(text)
                ? ConditionalRecurrenceRule.HolidayPolicy.SKIP
                : containsAny(text, "放假就改", "國定假日就提前", "逢假日前移", "假日提前")
                        ? ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY
                        : ConditionalRecurrenceRule.HolidayPolicy.NONE;
        ConditionalRecurrenceRule.ClosurePolicy closure = containsAny(text,
                "颱風停班", "颱風停課", "停班就順延", "停課就順延", "停班課就順延")
                ? ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY
                : ConditionalRecurrenceRule.ClosurePolicy.NONE;
        return new Parsed(title, weekday, start, timePeriodExplicit, duration, endOfYear(text),
                holiday, closure, jurisdiction(text));
    }

    private ZonedDateTime nextOccurrence(DayOfWeek weekday, LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TAIPEI));
        int days = Math.floorMod(weekday.getValue() - now.getDayOfWeek().getValue(), 7);
        ZonedDateTime candidate = now.toLocalDate().plusDays(days).atTime(time).atZone(TAIPEI);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }

    private static Duration parseDuration(String text) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.find()) return null;
        Integer amount = number(matcher.group(1));
        if (amount == null || amount <= 0) return null;
        return matcher.group(2).equals("分鐘")
                ? Duration.ofMinutes(amount) : Duration.ofHours(amount);
    }

    private static LocalTime parseTime(Matcher matcher) {
        Integer hour = number(matcher.group(2));
        if (hour == null) return null;
        String minuteText = matcher.group(3);
        int minute = minuteText == null || minuteText.isBlank() ? 0
                : minuteText.equals("半") ? 30
                : Optional.ofNullable(number(minuteText.replace("分", ""))).orElse(-1);
        String period = matcher.group(1);
        hour = ChineseTimePeriod.toTwentyFourHour(period, hour);
        return hour < 0 || hour > 23 || minute < 0 || minute > 59
                ? null : LocalTime.of(hour, minute);
    }

    private static Integer number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace("兩", "二");
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.charAt(ten - 1));
            int units = ten == value.length() - 1 ? 0 : digit(value.charAt(ten + 1));
            return tens < 0 || units < 0 ? null : tens * 10 + units;
        }
        int single = value.length() == 1 ? digit(value.charAt(0)) : -1;
        return single < 0 ? null : single;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static DayOfWeek weekday(String value) {
        return switch (value.charAt(0)) {
            case '一' -> DayOfWeek.MONDAY;
            case '二' -> DayOfWeek.TUESDAY;
            case '三' -> DayOfWeek.WEDNESDAY;
            case '四' -> DayOfWeek.THURSDAY;
            case '五' -> DayOfWeek.FRIDAY;
            case '六' -> DayOfWeek.SATURDAY;
            case '日', '天' -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("unsupported weekday: " + value);
        };
    }

    private LocalDate endOfYear(String text) {
        return text.contains("年底")
                ? LocalDate.of(LocalDate.now(clock.withZone(TAIPEI)).getYear(), 12, 31) : null;
    }

    private static String jurisdiction(String text) {
        for (String value : List.of("臺北市", "台北市", "新北市", "桃園市", "臺中市", "台中市",
                "臺南市", "台南市", "高雄市", "基隆市", "新竹市", "新竹縣", "苗栗縣",
                "彰化縣", "南投縣", "雲林縣", "嘉義市", "嘉義縣", "屏東縣", "宜蘭縣",
                "花蓮縣", "臺東縣", "台東縣", "澎湖縣", "金門縣", "連江縣")) {
            if (text.contains(value)) return value.replace('台', '臺');
        }
        return null;
    }

    private static String cleanTitle(String value) {
        String title = value == null ? "" : value.replaceFirst("^(?:開|上|排|做|去)", "").strip();
        return title.isBlank() ? null : title;
    }

    private static boolean isConditionalRecurrence(String text) {
        boolean conditional = containsAny(text, "國定假日", "放假", "颱風", "停班", "停課");
        return conditional && (hasExplicitRecurrence(text)
                || (text.contains("週會") && WEEKDAY.matcher(text).find()));
    }

    private static boolean hasExplicitRecurrence(String text) {
        return containsAny(text, "每週", "每星期", "每個禮拜", "固定");
    }

    private static boolean asksHolidaySkip(String text) {
        return containsAny(text, "假日不用", "放假不用", "國定假日不", "假日跳過");
    }

    private static boolean looksLikeMeta(String text) {
        return containsAny(text, "情境清單", "測試資料", "功能開發", "需求文件", "使用者說");
    }

    private static String holidayLabel(ConditionalRecurrenceRule.HolidayPolicy policy) {
        return switch (policy) {
            case PREVIOUS_BUSINESS_DAY -> "提前到前一個確認上班日";
            case SKIP -> "確認為國定假日就跳過本次";
            case NONE -> "不調整";
        };
    }

    private static String closureLabel(ConditionalRecurrenceRule rule) {
        return rule.getClosurePolicy() == ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY
                ? rule.getClosureJurisdiction() + "停班停課時順延到下一個確認上班日" : "不調整";
    }

    private static String makeupLabel(ConditionalRecurrenceRule rule) {
        return rule.getHolidayPolicy() == ConditionalRecurrenceRule.HolidayPolicy.SKIP
                ? "不自動建立；老師另行提供時間後再建單次行程"
                : "依使用者後續明確資訊另行處理";
    }

    private static String resumePendingDuration(String current, ConversationSnapshot previous) {
        if (current.isBlank() || isConditionalRecurrence(current) || previous == null
                || !"CLARIFICATION_NEEDED".equals(previous.lastAction())
                || previous.lastUserText() == null || previous.lastAssistantText() == null
                || !previous.lastAssistantText().contains("條件式週期")
                || !previous.lastAssistantText().contains("每次持續多久")
                || !DURATION.matcher(current).find()) {
            return current;
        }
        return normalize(previous.lastUserText()) + "，" + current;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private record Parsed(
            String title,
            DayOfWeek weekday,
            LocalTime startTime,
            boolean timePeriodExplicit,
            Duration duration,
            LocalDate until,
            ConditionalRecurrenceRule.HolidayPolicy holidayPolicy,
            ConditionalRecurrenceRule.ClosurePolicy closurePolicy,
            String jurisdiction) {
    }
}
