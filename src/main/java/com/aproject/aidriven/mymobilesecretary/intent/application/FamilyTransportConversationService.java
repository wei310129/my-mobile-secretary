package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 明確指定其他家人送、接時，建立家庭可見但不占用本人忙碌的兩個鐘點事件。 */
@Service
@Transactional
public class FamilyTransportConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String PERSON = "老婆|老公|太太|先生|媽媽|爸爸|阿公|阿嬤|外公|外婆|爺爺|奶奶";
    private static final String CHILD = "兒子|女兒|孩子|小孩";
    private static final String HOUR = "(?:\\d{1,2}|[一二三四五六七八九兩]|十(?:[一二三四五六七八九])?|二十(?:[一二三])?)";
    private static final String CLOCK = ChineseTimePeriod.NON_CAPTURING_REGEX + "?" + HOUR
            + "(?:點(?:半|[零一二三四五六七八九十兩\\d]{1,2}分?)?|:[0-5]\\d)";
    private static final Pattern DROP = Pattern.compile(
            "(?<time>" + CLOCK + ")(?<person>" + PERSON + ")送(?<child>" + CHILD
                    + ")(?:去|到)(?<destination>[^，,。；;]{1,30})");
    private static final Pattern PICK = Pattern.compile(
            "(?<time>" + CLOCK + ")(?<person>" + PERSON
                    + ")(?:去)?接(?:回)?(?<destination>[^，,。；;]{1,30})");
    private static final Pattern WEEKDAY = Pattern.compile("(?:週|星期|禮拜)([一二三四五六日天])");

    private final ScheduleService scheduleService;
    private final Clock clock;

    public FamilyTransportConversationService(ScheduleService scheduleService, Clock clock) {
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (looksLikeMeta(compact)) return Optional.empty();
        Matcher drop = DROP.matcher(compact);
        Matcher pickup = PICK.matcher(compact);
        Matcher weekday = WEEKDAY.matcher(compact);
        if (!drop.find() || !pickup.find() || !weekday.find()) return Optional.empty();

        LocalTime dropTime = parseTime(drop.group("time"), false);
        LocalTime pickupTime = parseTime(pickup.group("time"), true);
        if (dropTime == null || pickupTime == null) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "已辨識家人分工，但送達或接回時間仍不明確；確認前不會建立，也不會算成由你接送。"));
        }
        LocalDate date = nextDate(dayOfWeek(weekday.group(1)), dropTime);
        String dropTitle = "%s送%s到%s".formatted(
                drop.group("person"), drop.group("child"), drop.group("destination"));
        String pickupTitle = "%s接%s回%s".formatted(
                pickup.group("person"), drop.group("child"), pickup.group("destination"));
        beforeMutation.run();
        scheduleService.createFamilyPointSchedule(dropTitle,
                at(date, dropTime), null, drop.group("person"));
        scheduleService.createFamilyPointSchedule(pickupTitle,
                at(date, pickupTime), null, pickup.group("person"));

        String message = ("已建立 2 個家庭接送鐘點（原話沒有持續時間，所以不會暗自補時長）：\n"
                        + "1. %s｜%s｜負責人：%s\n2. %s｜%s｜負責人：%s\n\n"
                        + "兩筆都只供家庭查閱，不會算成你的忙碌或寫成由你接送。")
                .formatted(dropTitle, label(date, dropTime), drop.group("person"),
                        pickupTitle, label(date, pickupTime), pickup.group("person"));
        return Optional.of(IntentResult.message(IntentResult.Action.BATCH_EXECUTED, message));
    }

    private LocalDate nextDate(DayOfWeek wanted, LocalTime firstEventTime) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TAIPEI));
        int days = Math.floorMod(wanted.getValue() - now.getDayOfWeek().getValue(), 7);
        LocalDate candidate = now.toLocalDate().plusDays(days);
        if (days == 0 && !candidate.atTime(firstEventTime).atZone(TAIPEI).isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private static java.time.Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TAIPEI).toInstant();
    }

    private static String label(LocalDate date, LocalTime time) {
        return CalendarDatePolicy.format(date) + " " + time;
    }

    private static LocalTime parseTime(String raw, boolean pickup) {
        Matcher matcher = Pattern.compile(
                ChineseTimePeriod.CAPTURING_REGEX + "?(" + HOUR
                        + ")(?:點(?:(半)|([零一二三四五六七八九十兩\\d]{1,2})分?)?|:([0-5]\\d))")
                .matcher(raw);
        if (!matcher.matches()) return null;
        Integer hour = number(matcher.group(2));
        int minute = matcher.group(3) != null ? 30
                : matcher.group(4) != null ? number(matcher.group(4))
                : matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : 0;
        String period = matcher.group(1);
        hour = ChineseTimePeriod.toTwentyFourHour(period, hour);
        if (period == null && pickup && hour <= 7) hour += 12;
        return hour > 23 || minute > 59 ? null : LocalTime.of(hour, minute);
    }

    private static Integer number(String raw) {
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

    private static DayOfWeek dayOfWeek(String value) {
        return switch (value.charAt(0)) {
            case '一' -> DayOfWeek.MONDAY;
            case '二' -> DayOfWeek.TUESDAY;
            case '三' -> DayOfWeek.WEDNESDAY;
            case '四' -> DayOfWeek.THURSDAY;
            case '五' -> DayOfWeek.FRIDAY;
            case '六' -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private static boolean looksLikeMeta(String text) {
        return List.of("情境清單", "測試資料", "功能開發", "使用者說").stream()
                .anyMatch(text::contains);
    }
}
