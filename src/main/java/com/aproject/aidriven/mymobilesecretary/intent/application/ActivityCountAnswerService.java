package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 確定性回答「某段期間做某活動幾次」，並區分完成紀錄與只有排程的紀錄。 */
@Service
public class ActivityCountAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final List<String> COUNT_SUFFIXES = List.of("總共幾次", "一共幾次", "有多少次", "多少次", "幾次");

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final Clock clock;

    public ActivityCountAnswerService(TaskService taskService, ScheduleService scheduleService, Clock clock) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text) {
        Optional<CountQuery> query = parse(text, clock);
        return query.map(value -> answer(value.topic(), value.range()));
    }

    public IntentResult answerTopic(String topic, String filter) {
        DateRange range = rangeForFilter(filter, clock);
        if (range == null) {
            return IntentResult.clarificationNeeded(
                    "要統計哪個期間？請說上個月、本月、上週或本週。");
        }
        return answer(topic, range);
    }

    private IntentResult answer(String topic, DateRange range) {
        Instant now = Instant.now(clock);
        List<ScheduleItem> schedules = scheduleService.listSchedules(null);
        long completedSchedules = schedules.stream()
                .filter(item -> item.getStatus() == ScheduleStatus.COMPLETED)
                .filter(item -> LastActivityAnswerService.matchesTopic(topic, item.getTitle()))
                .filter(item -> range.contains(item.getStartAt()))
                .count();
        long unverifiedSchedules = schedules.stream()
                .filter(item -> item.getStatus() == ScheduleStatus.CONFIRMED)
                .filter(item -> !item.getEndAt().isAfter(now))
                .filter(item -> LastActivityAnswerService.matchesTopic(topic, item.getTitle()))
                .filter(item -> range.contains(item.getStartAt()))
                .count();
        long completedTasks = taskService.listCompletedTasks().stream()
                .filter(task -> LastActivityAnswerService.matchesTopic(topic, task.getTitle()))
                .filter(task -> range.contains(task.getUpdatedAt()))
                .count();
        long verified = completedSchedules + completedTasks;
        long total = verified + unverifiedSchedules;

        String message = "📊 %s「%s」紀錄：\n- 範圍｜%s–%s\n- 總計｜%d 次\n- 已完成回報｜%d 次"
                .formatted(range.label(), topic, range.from().format(DATE), range.to().format(DATE),
                        total, verified);
        message += "\n- 只有已確認行程｜%d 次".formatted(unverifiedSchedules);
        if (unverifiedSchedules > 0) {
            message += "\n\n⚠️ 有 %d 次只有排入行程，沒有完成回報。"
                    .formatted(unverifiedSchedules)
                    + "\n- 總計代表系統中的活動紀錄，不保證每次都實際完成";
        } else if (total == 0) {
            message += "\n\n🔎 這段期間沒有符合的紀錄。"
                    + "\n- 如果當時用了別的名稱，請告訴我那個行程或待辦名稱";
        }
        return IntentResult.message(IntentResult.Action.ACTIVITY_COUNT_INFO, message);
    }

    static Optional<CountQuery> parse(String text, Clock clock) {
        String normalized = normalize(text);
        if (normalized.isBlank() || normalized.contains("買") || normalized.contains("購買")) {
            return Optional.empty();
        }
        DateRange range = rangeFromText(normalized, clock);
        if (range == null) {
            return Optional.empty();
        }
        int periodAt = normalized.indexOf(range.matchedText());
        String afterPeriod = normalized.substring(periodAt + range.matchedText().length());
        for (String suffix : COUNT_SUFFIXES) {
            int suffixAt = afterPeriod.indexOf(suffix);
            if (suffixAt < 0) {
                continue;
            }
            String topic = afterPeriod.substring(0, suffixAt)
                    .replaceFirst("^(?:我|有|做)", "")
                    .replaceFirst("(?:做了|共有|了)$", "");
            if (!topic.isBlank()) {
                return Optional.of(new CountQuery(topic, range));
            }
        }
        return Optional.empty();
    }

    private static DateRange rangeFromText(String text, Clock clock) {
        if (text.contains("上個月")) return rangeForFilter("LAST_MONTH", clock, "上個月");
        if (text.contains("上月")) return rangeForFilter("LAST_MONTH", clock, "上月");
        if (text.contains("這個月")) return rangeForFilter("THIS_MONTH", clock, "這個月");
        if (text.contains("本月")) return rangeForFilter("THIS_MONTH", clock, "本月");
        if (text.contains("上週")) return rangeForFilter("LAST_WEEK", clock, "上週");
        if (text.contains("上周")) return rangeForFilter("LAST_WEEK", clock, "上周");
        if (text.contains("這週")) return rangeForFilter("THIS_WEEK", clock, "這週");
        if (text.contains("本週")) return rangeForFilter("THIS_WEEK", clock, "本週");
        return null;
    }

    private static DateRange rangeForFilter(String filter, Clock clock) {
        return rangeForFilter(filter, clock, filter == null ? "" : filter);
    }

    private static DateRange rangeForFilter(String filter, Clock clock, String matchedText) {
        if (filter == null) return null;
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        return switch (filter.toUpperCase(Locale.ROOT)) {
            case "LAST_MONTH" -> {
                LocalDate month = today.minusMonths(1);
                yield new DateRange(month.withDayOfMonth(1), month.with(TemporalAdjusters.lastDayOfMonth()),
                        "上個月", matchedText);
            }
            case "THIS_MONTH", "MONTH" -> new DateRange(today.withDayOfMonth(1), today,
                    "本月", matchedText);
            case "LAST_WEEK" -> {
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new DateRange(thisMonday.minusWeeks(1), thisMonday.minusDays(1),
                        "上週", matchedText);
            }
            case "THIS_WEEK", "WEEK" -> new DateRange(
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today,
                    "本週", matchedText);
            default -> null;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value
                .replaceAll("[\\s「」『』，,。.!！?？:：]", "")
                .toLowerCase(Locale.ROOT);
    }

    record CountQuery(String topic, DateRange range) {
    }

    record DateRange(LocalDate from, LocalDate to, String label, String matchedText) {
        boolean contains(Instant instant) {
            LocalDate date = ZonedDateTime.ofInstant(instant, TAIPEI).toLocalDate();
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }
}
