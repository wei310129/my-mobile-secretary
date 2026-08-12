package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ScheduleClarificationDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraft;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 將「每月第 N 個星期幾」保存成專用週期，禁止降級成每週。 */
@Service
public class MonthlyOrdinalRecurrenceConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern RULE = Pattern.compile(
            "第([一二三四五1-5])個?(?:週|星期|禮拜)([一二三四五六日天])");
    private static final Pattern TIME = Pattern.compile(
            ChineseTimePeriod.CAPTURING_REGEX + "?([零一二三四五六七八九十兩\\d]{1,3})"
                    + "(?:點|:)(半|[零一二三四五六七八九十兩\\d]{1,2}分?|[0-5]\\d)?");
    private static final Pattern DURATION = Pattern.compile(
            "(?:每次|維持|持續|開|上|排)?([零一二三四五六七八九十兩\\d]{1,3})(小時|鐘頭|分鐘)");
    private static final Pattern TITLE = Pattern.compile(
            "(?:點半|點|分)(?:開始)?(?:開|上|排|做|去)?([^，,。；;]{1,40}?)(?:，|,|。|；|;|之後|每次|$)");

    private final ScheduleService scheduleService;
    private final Clock clock;
    private ScheduleClarificationDraftService clarificationDrafts;

    public MonthlyOrdinalRecurrenceConversationService(ScheduleService scheduleService, Clock clock) {
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setClarificationDrafts(ScheduleClarificationDraftService clarificationDrafts) {
        this.clarificationDrafts = clarificationDrafts;
    }

    public Optional<IntentResult> answer(
            String text, ConversationSnapshot previous, Runnable beforeMutation) {
        String current = normalize(text);
        if (looksLikeMeta(current)) return Optional.empty();
        Optional<ScheduleClarificationDraft> pending = clarificationDrafts == null
                ? Optional.empty()
                : clarificationDrafts.findCurrent(ScheduleClarificationCapability.MONTHLY_ORDINAL);
        String compact = pending.isPresent() ? current : resumePendingDuration(current, previous);
        boolean initialRequest = isMonthlyOrdinalRule(compact);
        if (!initialRequest && pending.isEmpty()) return Optional.empty();
        if (!initialRequest && clarificationDrafts != null
                && !clarificationDrafts.mayConsumeContinuation(
                        ScheduleClarificationCapability.MONTHLY_ORDINAL)) {
            return Optional.empty();
        }

        Parsed parsed = parse(compact);
        if (pending.isPresent() && !hasTypedInput(parsed)) return Optional.empty();
        Integer monthOffset = compact.contains("下個月") ? 1 : null;
        if (clarificationDrafts != null) {
            beforeMutation.run();
            ScheduleClarificationDraft draft = clarificationDrafts.monthly(
                    parsed.ordinal(), monthOffset, parsed.weekday(), parsed.startTime(),
                    parsed.timePeriodExplicit(), durationMinutes(parsed.duration()), parsed.title());
            parsed = fromDraft(draft);
            monthOffset = draft.getMonthOffset();
        }
        List<ClarificationStep> missing = new ArrayList<>();
        if (parsed.ordinal() == null || parsed.weekday() == null) missing.add(
                ClarificationStep.blocking("monthly-ordinal.rule", "ordinalWeekday",
                        "要排每月第幾個星期幾？", 10));
        if (parsed.startTime() == null) missing.add(ClarificationStep.blocking(
                "monthly-ordinal.start-time", "startTime", "幾點開始？", 20));
        if (parsed.startTime() != null && !parsed.timePeriodExplicit()) {
            missing.add(ClarificationStep.blocking(
                    "monthly-ordinal.time-period", "timePeriod",
                    "這個鐘點是上午還是晚上？", 30));
        }
        if (parsed.duration() == null) missing.add(ClarificationStep.blocking(
                "monthly-ordinal.duration", "duration", "每次持續多久？", 40));
        if (parsed.title() == null) missing.add(ClarificationStep.blocking(
                "monthly-ordinal.title", "title", "這個行程要叫什麼名稱？", 50));
        if (!missing.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我支援「每月第 N 個星期幾」的專用週期，絕不會拆成每週行程。"
                            + "資訊補齊前不建立行程。",
                    ClarificationStep.first(missing)));
        }

        Optional<LocalDate> firstDate = firstOccurrence(monthOffset, parsed.ordinal(), parsed.weekday(),
                parsed.startTime());
        if (firstDate.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "指定月份沒有第 %d 個%s，沒有建立行程；請改月份或週次。"
                            .formatted(parsed.ordinal(), weekdayLabel(parsed.weekday()))));
        }
        ZonedDateTime start = firstDate.get().atTime(parsed.startTime()).atZone(TAIPEI);
        beforeMutation.run();
        IntentResult result = IntentResult.scheduleDecided(scheduleService.createSchedule(
                parsed.title(), start.toInstant(), start.plus(parsed.duration()).toInstant(), null,
                ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY));
        if (clarificationDrafts != null) {
            clarificationDrafts.complete(ScheduleClarificationCapability.MONTHLY_ORDINAL);
        }
        return Optional.of(result);
    }

    private Parsed parse(String text) {
        Matcher rule = RULE.matcher(text);
        Integer ordinal = null;
        DayOfWeek weekday = null;
        int timeRegionStart = 0;
        if (rule.find()) {
            ordinal = number(rule.group(1));
            weekday = weekday(rule.group(2));
            timeRegionStart = rule.end();
        }
        Matcher time = TIME.matcher(text);
        time.region(Math.min(timeRegionStart, text.length()), text.length());
        LocalTime startTime = null;
        boolean explicit = false;
        if (time.find()) {
            Integer hour = number(time.group(2));
            int minute = minute(time.group(3));
            if (hour != null) {
                explicit = time.group(1) != null || hour >= 13;
                hour = ChineseTimePeriod.toTwentyFourHour(time.group(1), hour);
                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                    startTime = LocalTime.of(hour, minute);
                }
            }
        }
        Duration duration = duration(text);
        Matcher title = TITLE.matcher(text);
        String titleValue = title.find() ? cleanTitle(title.group(1)) : null;
        return new Parsed(ordinal, weekday, startTime, explicit, duration, titleValue);
    }

    private Optional<LocalDate> firstOccurrence(
            Integer monthOffset, int ordinal, DayOfWeek weekday, LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TAIPEI));
        YearMonth month = YearMonth.from(now);
        if (monthOffset != null) {
            return dateIn(month.plusMonths(monthOffset), ordinal, weekday);
        }
        for (int offset = 0; offset < 24; offset++) {
            Optional<LocalDate> date = dateIn(month.plusMonths(offset), ordinal, weekday);
            if (date.isPresent() && date.get().atTime(time).atZone(TAIPEI).isAfter(now)) return date;
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> dateIn(YearMonth month, int ordinal, DayOfWeek weekday) {
        LocalDate date = month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday));
        return YearMonth.from(date).equals(month) ? Optional.of(date) : Optional.empty();
    }

    private static Duration duration(String text) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.find()) return null;
        Integer amount = number(matcher.group(1));
        if (amount == null || amount <= 0) return null;
        return matcher.group(2).equals("分鐘")
                ? Duration.ofMinutes(amount) : Duration.ofHours(amount);
    }

    private static int minute(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        if (raw.equals("半")) return 30;
        Integer value = number(raw.replace("分", ""));
        return value == null ? -1 : value;
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

    private static String weekdayLabel(DayOfWeek weekday) {
        return "星期" + "一二三四五六日".charAt(weekday.getValue() - 1);
    }

    private static boolean isMonthlyOrdinalRule(String text) {
        return RULE.matcher(text).find()
                && (text.contains("每月") || text.contains("每個月")
                        || (text.contains("下個月") && text.contains("之後")));
    }

    private static String resumePendingDuration(String current, ConversationSnapshot previous) {
        if (current.isBlank() || isMonthlyOrdinalRule(current) || previous == null
                || !"CLARIFICATION_NEEDED".equals(previous.lastAction())
                || previous.lastUserText() == null || previous.lastAssistantText() == null
                || !previous.lastAssistantText().contains("每月第 N 個星期幾")
                || !DURATION.matcher(current).find()) {
            return current;
        }
        return normalize(previous.lastUserText()) + "，" + current;
    }

    private static String cleanTitle(String value) {
        String title = value == null ? "" : value.replaceFirst("^(?:開|上|排|做|去)", "").strip();
        return title.isBlank() ? null : title;
    }

    private static boolean looksLikeMeta(String text) {
        return text.contains("情境清單") || text.contains("測試資料")
                || text.contains("功能開發") || text.contains("需求文件") || text.contains("使用者說");
    }

    private static boolean hasTypedInput(Parsed parsed) {
        return parsed.ordinal() != null || parsed.weekday() != null || parsed.startTime() != null
                || parsed.duration() != null || parsed.title() != null;
    }

    private static Integer durationMinutes(Duration duration) {
        return duration == null ? null : Math.toIntExact(duration.toMinutes());
    }

    private static Parsed fromDraft(ScheduleClarificationDraft draft) {
        return new Parsed(draft.getOrdinalValue(), draft.getWeekday(), draft.getStartTime(),
                draft.isTimePeriodExplicit(),
                draft.getDurationMinutes() == null ? null
                        : Duration.ofMinutes(draft.getDurationMinutes()),
                draft.getTitle());
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private record Parsed(
            Integer ordinal, DayOfWeek weekday, LocalTime startTime,
            boolean timePeriodExplicit, Duration duration, String title) {
    }
}
