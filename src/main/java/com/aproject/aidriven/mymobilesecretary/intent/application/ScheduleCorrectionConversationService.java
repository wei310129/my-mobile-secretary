package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 同一句口語連續改口時只採最後更正，並保留既有行程時長與地點。 */
@Service
@Transactional
public class ScheduleCorrectionConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String HOUR =
            "(?:\\d{1,2}|[一二三四五六七八九兩]|十(?:[一二三四五六七八九])?|二十(?:[一二三])?)";
    private static final String CLOCK =
            ChineseTimePeriod.NON_CAPTURING_REGEX + "?" + HOUR
                    + "(?:點(?:半|[零一二三四五六七八九十兩\\d]{1,2}分?)?|:[0-5]\\d)";
    private static final Pattern SOURCE = Pattern.compile(
            "明天(?<time>" + CLOCK + ")的?(?<subject>[^，,。；;]{0,16}?)(?=改)");
    private static final Pattern CORRECTION = Pattern.compile("改(?:到|成)?(?<time>" + CLOCK + ")");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final ScheduleService scheduleService;
    private final Clock clock;

    public ScheduleCorrectionConversationService(ScheduleService scheduleService, Clock clock) {
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (looksLikeMeta(compact) || !(compact.contains("不是") || compact.contains("說錯"))) {
            return Optional.empty();
        }
        if (compact.contains("地點改成") || compact.contains("地點改到")) {
            return Optional.empty();
        }

        Matcher source = SOURCE.matcher(compact);
        if (!source.find() || !looksLikeMeeting(source.group("subject"))) {
            return Optional.empty();
        }
        String inheritedPeriod = periodOf(source.group("time"));
        LocalTime originalTime = parseTime(source.group("time"), null);
        Matcher corrections = CORRECTION.matcher(compact);
        LocalTime finalTime = null;
        int correctionCount = 0;
        while (corrections.find()) {
            correctionCount++;
            finalTime = parseTime(corrections.group("time"), inheritedPeriod);
        }
        if (correctionCount < 2 || originalTime == null || finalTime == null) {
            return Optional.empty();
        }

        LocalDate date = ZonedDateTime.now(clock.withZone(TAIPEI)).toLocalDate().plusDays(1);
        Instant originalStart = date.atTime(originalTime).atZone(TAIPEI).toInstant();
        List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesStartingBetween(
                originalStart, originalStart.plusSeconds(60));
        if (matches.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "找不到明天 %s 開始的既有會議；我沒有新增行程，也沒有套用前一個或最後一個時間。"
                            .formatted(originalTime)));
        }
        if (matches.size() > 1) {
            String choices = matches.stream()
                    .map(item -> "#%s %s".formatted(item.getId(), item.getTitle()))
                    .collect(Collectors.joining("、"));
            return Optional.of(IntentResult.clarificationNeeded(
                    "明天 %s 有多個可改期行程（%s），請指定名稱或編號；目前都沒有修改。"
                            .formatted(originalTime, choices)));
        }

        ScheduleItem item = matches.getFirst();
        if (item.getRecurrence() != ScheduleItem.Recurrence.NONE) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "「%s」是固定行程；請確認只改明天這一次，還是修改整個系列。目前沒有修改。"
                            .formatted(item.getTitle())));
        }
        Duration duration = Duration.between(item.getStartAt(), item.getEndAt());
        Instant newStart = date.atTime(finalTime).atZone(TAIPEI).toInstant();
        beforeMutation.run();
        ScheduleDecision decision = scheduleService.reschedule(
                item.getId(), newStart, newStart.plus(duration));
        String feasibility = decision.feasibility().feasible()
                ? "可行並已確認" : "新時段仍有衝突，需要你決定";
        String place = compact.contains("地點一樣")
                ? "地點設定保持不變" : "原地點保持不變";
        String message = ("已依最後一次更正，把「%s」改到 %s–%s；前面說的時間未採用。"
                        + "原本 %d 分鐘與%s，%s；沒有新增重複行程。")
                .formatted(item.getTitle(),
                        newStart.atZone(TAIPEI).format(DATE_TIME),
                        newStart.plus(duration).atZone(TAIPEI).toLocalTime(),
                        duration.toMinutes(), place, feasibility);
        return Optional.of(new IntentResult(
                IntentResult.Action.SCHEDULE_RESCHEDULED, message, null, decision));
    }

    private static boolean looksLikeMeeting(String subject) {
        return subject != null && (subject.contains("會") || subject.contains("會議"));
    }

    private static String periodOf(String raw) {
        return ChineseTimePeriod.leadingPeriod(raw);
    }

    private static LocalTime parseTime(String raw, String defaultPeriod) {
        Matcher matcher = Pattern.compile(
                ChineseTimePeriod.CAPTURING_REGEX + "?(" + HOUR
                        + ")(?:點(?:(半)|([零一二三四五六七八九十兩\\d]{1,2})分?)?|:([0-5]\\d))")
                .matcher(raw);
        if (!matcher.matches()) return null;
        int hour = number(matcher.group(2));
        int minute = matcher.group(3) != null ? 30
                : matcher.group(4) != null ? number(matcher.group(4))
                : matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : 0;
        String period = matcher.group(1) == null ? defaultPeriod : matcher.group(1);
        hour = ChineseTimePeriod.toTwentyFourHour(period, hour);
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
