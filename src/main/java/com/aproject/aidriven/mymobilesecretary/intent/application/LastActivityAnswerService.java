package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 確定性回答「我上次做某件事是何時」，AI 故障時也不會把問句存成待辦。 */
@Service
public class LastActivityAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy/MM/dd（EEE）HH:mm", Locale.TAIWAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> QUESTION_SUFFIXES = List.of(
            "是什麼時候", "在什麼時候", "什麼時候", "是哪一天", "是哪天", "何時", "哪一天", "哪天");
    private static final List<Set<String>> SYNONYM_GROUPS = List.of(
            Set.of("運動", "健身", "跑步", "慢跑", "重訓", "游泳", "瑜伽", "騎單車", "騎腳踏車"),
            Set.of("看醫生", "就醫", "回診", "門診"),
            Set.of("剪頭髮", "理髮"));

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final Clock clock;

    public LastActivityAnswerService(TaskService taskService, ScheduleService scheduleService, Clock clock) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text) {
        Optional<String> requestedTopic = requestedTopic(text);
        if (requestedTopic.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(answerTopic(requestedTopic.get()));
    }

    public IntentResult answerTopic(String topic) {
        Instant now = Instant.now(clock);
        List<Activity> scheduleActivities = scheduleService.listSchedules(null).stream()
                .filter(item -> isPastActivity(item, now))
                .filter(item -> matchesTopic(topic, item.getTitle()))
                .map(Activity::fromSchedule)
                .toList();
        List<Activity> taskActivities = taskService.listCompletedTasks().stream()
                .filter(task -> matchesTopic(topic, task.getTitle()))
                .map(task -> new Activity(task.getTitle(), task.getUpdatedAt(), null,
                        Source.COMPLETED_TASK))
                .toList();

        Optional<Activity> latest = java.util.stream.Stream
                .concat(scheduleActivities.stream(), taskActivities.stream())
                .max(Comparator.comparing(Activity::occurredAt));
        return latest.map(activity -> answerFound(topic, activity))
                .orElseGet(() -> answerMissing(topic));
    }

    static Optional<String> requestedTopic(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank() || isPurchaseQuestion(normalized)) {
            return Optional.empty();
        }

        int marker = normalized.indexOf("最近一次");
        int markerLength = "最近一次".length();
        if (marker < 0) {
            marker = normalized.indexOf("上次");
            markerLength = "上次".length();
        }
        if (marker >= 0) {
            String afterMarker = normalized.substring(marker + markerLength);
            for (String suffix : QUESTION_SUFFIXES) {
                int suffixAt = afterMarker.indexOf(suffix);
                if (suffixAt >= 0) {
                    return cleanTopic(afterMarker.substring(0, suffixAt));
                }
            }
        }

        int distanceMarker = normalized.indexOf("距離上次");
        int durationAt = normalized.lastIndexOf("多久");
        if (distanceMarker >= 0 && durationAt > distanceMarker + "距離上次".length()) {
            return cleanTopic(normalized.substring(distanceMarker + "距離上次".length(), durationAt));
        }
        String withoutPronoun = normalized.startsWith("我") ? normalized.substring(1) : normalized;
        if (withoutPronoun.startsWith("多久沒")) {
            return cleanTopic(withoutPronoun.substring("多久沒".length())
                    .replaceFirst("了$", ""));
        }
        return Optional.empty();
    }

    private static Optional<String> cleanTopic(String value) {
        String topic = value.replaceFirst("^(?:我|去|做|進行)", "")
                .replaceFirst("(?:了|過)$", "")
                .replaceFirst("^(?:我|去|做|進行)", "");
        return topic.isBlank() ? Optional.empty() : Optional.of(topic);
    }

    private static boolean isPurchaseQuestion(String text) {
        return text.contains("買") || text.contains("購買") || text.contains("價格")
                || text.contains("多少錢") || text.contains("花多少");
    }

    private static boolean isPastActivity(ScheduleItem item, Instant now) {
        return item.getStatus() == ScheduleStatus.COMPLETED
                || (item.getStatus() == ScheduleStatus.CONFIRMED && !item.getEndAt().isAfter(now));
    }

    private static boolean matchesTopic(String topic, String title) {
        String normalizedTopic = normalize(topic);
        String normalizedTitle = normalize(title).replaceFirst("^(?:提醒|去|做|進行)", "");
        if (normalizedTopic.isBlank() || normalizedTitle.isBlank()) {
            return false;
        }
        if (normalizedTitle.contains(normalizedTopic) || normalizedTopic.contains(normalizedTitle)) {
            return true;
        }
        return SYNONYM_GROUPS.stream().anyMatch(group ->
                group.stream().anyMatch(normalizedTopic::contains)
                        && group.stream().anyMatch(normalizedTitle::contains));
    }

    private static IntentResult answerFound(String topic, Activity activity) {
        String interval = format(activity.occurredAt());
        if (activity.endedAt() != null) {
            interval += "–" + ZonedDateTime.ofInstant(activity.endedAt(), TAIPEI).format(TIME);
        }
        String message = "🕘 最近一次「%s」紀錄：\n- 紀錄｜%s\n- 時間｜%s\n- 來源｜%s"
                .formatted(topic, activity.title(), interval, activity.source().label);
        if (activity.source() == Source.CONFIRMED_PAST_SCHEDULE) {
            message += "\n\n⚠️ 這筆行程沒有完成回報。"
                    + "\n- 我只能確認當時有排入行程，不能確定實際完成。";
        }
        return IntentResult.message(IntentResult.Action.RECENT_ACTIVITY_LISTED, message);
    }

    private static IntentResult answerMissing(String topic) {
        return IntentResult.message(IntentResult.Action.RECENT_ACTIVITY_LISTED,
                "🔎 找不到「%s」的過往紀錄。\n- 我沒有新增任何待辦"
                        .formatted(topic)
                        + "\n- 如果當時用了別的名稱，請告訴我那個行程或待辦名稱");
    }

    private static String normalize(String value) {
        return value == null ? "" : value
                .replaceAll("[\\s「」『』，,。.!！?？:：]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private enum Source {
        COMPLETED_SCHEDULE("已完成行程"),
        CONFIRMED_PAST_SCHEDULE("已確認的過往行程"),
        COMPLETED_TASK("已完成待辦");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }

    private record Activity(String title, Instant occurredAt, Instant endedAt, Source source) {
        private static Activity fromSchedule(ScheduleItem item) {
            Source source = item.getStatus() == ScheduleStatus.COMPLETED
                    ? Source.COMPLETED_SCHEDULE : Source.CONFIRMED_PAST_SCHEDULE;
            return new Activity(item.getTitle(), item.getStartAt(), item.getEndAt(), source);
        }
    }
}
