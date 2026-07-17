package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 確定性回答「我上次做某件事是何時」，AI 故障時也不會把問句存成待辦。 */
@Service
public class LastActivityAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy/MM/dd（EEE）HH:mm", Locale.TAIWAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> QUESTION_SUFFIXES = List.of(
            "是什麼時候", "在什麼時候", "什麼時候", "是哪一天", "是哪天", "是何時", "何時", "哪一天", "哪天");
    private static final List<Set<String>> SYNONYM_GROUPS = List.of(
            Set.of("運動", "健身", "跑步", "慢跑", "重訓", "游泳", "瑜伽", "騎單車", "騎腳踏車"),
            Set.of("看醫生", "就醫", "回診", "門診"),
            Set.of("剪頭髮", "理髮"));
    private static final List<String> PLACE_ACTIVITY_TERMS = List.of(
            "騎腳踏車", "騎單車", "運動", "健身", "跑步", "慢跑", "重訓", "游泳", "瑜伽");

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final PlaceService placeService;
    private final Clock clock;

    public LastActivityAnswerService(TaskService taskService, ScheduleService scheduleService,
                                     PlaceService placeService, Clock clock) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.placeService = placeService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text) {
        Optional<PlaceActivityQuery> placeQuery = requestedPlaceActivity(text);
        if (placeQuery.isPresent()) {
            PlaceActivityQuery query = placeQuery.get();
            return Optional.of(answerPlaceTopic(
                    query.topic(), query.placeName(), query.mode(), query.allBranches()));
        }
        Optional<String> requestedTopic = requestedTopic(text);
        if (requestedTopic.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(answerTopic(requestedTopic.get()));
    }

    public IntentResult answerTopic(String topic) {
        return answerTopic(topic, null, null);
    }

    public IntentResult answerTopic(String topic, String placeName, String filter) {
        if (placeName != null && !placeName.isBlank()) {
            QueryMode mode = "EVER".equalsIgnoreCase(filter) ? QueryMode.EVER : QueryMode.LAST;
            boolean allBranches = "ALL_BRANCHES".equalsIgnoreCase(filter);
            return answerPlaceTopic(topic, placeName, mode, allBranches);
        }

        List<Activity> activities = activities(topic);
        Optional<Activity> latest = activities.stream()
                .max(Comparator.comparing(Activity::occurredAt));
        return latest.map(activity -> answerFound(topic, activity))
                .orElseGet(() -> answerMissing(topic));
    }

    private List<Activity> activities(String topic) {
        Instant now = Instant.now(clock);
        Map<Long, PlaceLabel> places = placeService.listPlaces().stream()
                .filter(place -> place.getId() != null)
                .collect(Collectors.toMap(Place::getId, PlaceLabel::from,
                        (first, ignored) -> first));
        List<Activity> scheduleActivities = scheduleService.listSchedules(null).stream()
                .filter(item -> isPastActivity(item, now))
                .filter(item -> matchesTopic(topic, item.getTitle()))
                .map(item -> Activity.fromSchedule(item, places.get(item.getPlaceId())))
                .toList();
        List<Activity> taskActivities = taskService.listCompletedTasks().stream()
                .filter(task -> matchesTopic(topic, task.getTitle()))
                .map(task -> new Activity(task.getTitle(), task.getUpdatedAt(), null,
                        Source.COMPLETED_TASK, null))
                .toList();

        return java.util.stream.Stream
                .concat(scheduleActivities.stream(), taskActivities.stream())
                .toList();
    }

    private IntentResult answerPlaceTopic(String topic, String placeName,
                                          QueryMode mode, boolean allBranches) {
        String displayPlace = displayPlaceQuery(placeName);
        List<Activity> matches = activities(topic).stream()
                .filter(activity -> activity.place() != null
                        && placeMatches(placeName, activity.place().name()))
                .toList();
        if (matches.isEmpty()) {
            String broader = isGymQuery(placeName)
                    ? "其他健身房的%s紀錄".formatted(topic)
                    : "其他地點的%s紀錄".formatted(topic);
            return IntentResult.message(IntentResult.Action.RECENT_ACTIVITY_LISTED,
                    "🔎 沒有找到在「%s」的%s紀錄。"
                            .formatted(displayPlace, topic)
                            + "\n\n❓ 要改查%s嗎？".formatted(broader));
        }

        List<String> branches = matches.stream()
                .map(activity -> activity.place().display())
                .distinct()
                .toList();
        if (mode == QueryMode.LAST && branches.size() > 1 && !allBranches) {
            return IntentResult.clarificationNeeded(
                    "「%s」有多個地點紀錄，請選一個分店，或說「所有分店」：\n%s"
                            .formatted(displayPlace, branches.stream()
                                    .map(branch -> "- " + branch)
                                    .collect(Collectors.joining("\n"))));
        }

        Activity latest = matches.stream()
                .max(Comparator.comparing(Activity::occurredAt))
                .orElseThrow();
        String heading = mode == QueryMode.EVER
                ? "✅ 有在「%s」%s的紀錄：".formatted(displayPlace, topic)
                : "🕘 最近一次在「%s」%s的紀錄：".formatted(displayPlace, topic);
        return answerFoundWithHeading(heading, latest);
    }

    private static Optional<PlaceActivityQuery> requestedPlaceActivity(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank() || normalized.length() > 80 || isPurchaseQuestion(normalized)
                || normalized.contains("使用者") || normalized.contains("你要告訴我")) {
            return Optional.empty();
        }

        QueryMode mode;
        int start;
        boolean durationQuestion = false;
        int marker = normalized.indexOf("最近一次");
        int markerLength = "最近一次".length();
        if (marker < 0) {
            marker = normalized.indexOf("上次");
            markerLength = "上次".length();
        }
        if (marker >= 0) {
            mode = QueryMode.LAST;
            start = marker + markerLength;
        } else if ((marker = normalized.indexOf("多久沒")) >= 0) {
            mode = QueryMode.LAST;
            start = marker + "多久沒".length();
            durationQuestion = true;
        } else {
            String[] everMarkers = {"之前有去", "之前有在", "有去", "有在", "去過"};
            int found = -1;
            int length = 0;
            for (String everMarker : everMarkers) {
                int candidate = normalized.indexOf(everMarker);
                if (candidate >= 0 && (found < 0 || candidate < found)) {
                    found = candidate;
                    length = everMarker.length();
                }
            }
            if (found < 0 || !normalized.endsWith("嗎")) {
                return Optional.empty();
            }
            mode = QueryMode.EVER;
            start = found + length;
        }

        String segment = normalized.substring(start).replaceFirst("^(?:去|在)", "");
        String activityTerm = PLACE_ACTIVITY_TERMS.stream()
                .filter(segment::contains)
                .max(Comparator.comparingInt(segment::lastIndexOf))
                .orElse(null);
        if (activityTerm == null) {
            return Optional.empty();
        }
        int activityAt = segment.lastIndexOf(activityTerm);
        if (activityAt <= 0) {
            return Optional.empty();
        }
        String place = segment.substring(0, activityAt);
        String tail = segment.substring(activityAt + activityTerm.length());
        if (mode == QueryMode.EVER && !Set.of("嗎", "過嗎", "過呢").contains(tail)) {
            return Optional.empty();
        }
        if (mode == QueryMode.LAST && !durationQuestion && QUESTION_SUFFIXES.stream().noneMatch(suffix ->
                tail.startsWith(suffix)
                        && isStandaloneQuestionTail(tail.substring(suffix.length())))) {
            return Optional.empty();
        }
        if (mode == QueryMode.LAST && durationQuestion
                && !Set.of("", "了", "呢", "嗎").contains(tail)) {
            return Optional.empty();
        }
        boolean allBranches = normalized.contains("分店")
                && (normalized.contains("所有") || normalized.contains("全部")
                || normalized.contains("各分店") || normalized.contains("任何"));
        if (allBranches) {
            place = place.replaceFirst("^(?:所有|全部|各|任何)", "").replace("分店", "");
        }
        return Optional.of(new PlaceActivityQuery(activityTerm, place, mode, allBranches));
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
                if (suffixAt >= 0 && isStandaloneQuestionTail(
                        afterMarker.substring(suffixAt + suffix.length()))) {
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

    /**
     * High-priority deterministic routing must only consume a standalone question. A quoted
     * question followed by correction or product feedback belongs to the interpreter instead of
     * turning the entire explanation into an activity topic.
     */
    private static boolean isStandaloneQuestionTail(String tail) {
        return tail.isBlank() || Set.of("呢", "啊", "呀", "了", "嗎", "嘛").contains(tail);
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

    static boolean isPastActivity(ScheduleItem item, Instant now) {
        return item.getStatus() == ScheduleStatus.COMPLETED
                || (item.getStatus() == ScheduleStatus.CONFIRMED && !item.getEndAt().isAfter(now));
    }

    static boolean matchesTopic(String topic, String title) {
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
        return answerFoundWithHeading("🕘 最近一次「%s」紀錄：".formatted(topic), activity);
    }

    private static IntentResult answerFoundWithHeading(String heading, Activity activity) {
        String interval = format(activity.occurredAt());
        if (activity.endedAt() != null) {
            interval += "–" + ZonedDateTime.ofInstant(activity.endedAt(), TAIPEI).format(TIME);
        }
        String message = "%s\n- 紀錄｜%s\n- 時間｜%s\n- 地點｜%s\n- 來源｜%s"
                .formatted(heading, activity.title(), interval,
                        activity.place() == null ? "未記錄" : activity.place().display(),
                        activity.source().label);
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

    private static boolean placeMatches(String query, String actual) {
        String normalizedQuery = normalize(query);
        String normalizedActual = normalize(actual);
        if (normalizedQuery.isBlank() || normalizedActual.isBlank()) {
            return false;
        }
        if (normalizedActual.contains(normalizedQuery)
                || normalizedQuery.contains(normalizedActual)) {
            return true;
        }
        if (normalizedQuery.contains("worldgym") && normalizedActual.contains("worldgym")) {
            String queryBranch = normalizedQuery.replace("worldgym", "").replace("分店", "");
            String actualBranch = normalizedActual.replace("worldgym", "")
                    .replace("分店", "").replaceFirst("店$", "");
            return queryBranch.isBlank() || actualBranch.contains(queryBranch)
                    || queryBranch.contains(actualBranch);
        }
        return false;
    }

    private static boolean isGymQuery(String placeName) {
        String normalized = normalize(placeName);
        return normalized.contains("gym") || normalized.contains("健身房");
    }

    private static String displayPlaceQuery(String placeName) {
        String value = placeName == null ? "" : placeName.strip();
        return value.replaceAll("(?i)world\\s*gym", "World Gym")
                .replace("worldgym", "World Gym");
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

    private enum QueryMode {
        LAST,
        EVER
    }

    private record PlaceActivityQuery(
            String topic, String placeName, QueryMode mode, boolean allBranches) {
    }

    private record PlaceLabel(String name, String address) {
        private static PlaceLabel from(Place place) {
            return new PlaceLabel(place.getName(), place.getAddress());
        }

        private String display() {
            return address == null || address.isBlank() ? name : "%s（%s）".formatted(name, address);
        }
    }

    private record Activity(
            String title, Instant occurredAt, Instant endedAt, Source source, PlaceLabel place) {
        private static Activity fromSchedule(ScheduleItem item, PlaceLabel place) {
            Source source = item.getStatus() == ScheduleStatus.COMPLETED
                    ? Source.COMPLETED_SCHEDULE : Source.CONFIRMED_PAST_SCHEDULE;
            return new Activity(item.getTitle(), item.getStartAt(), item.getEndAt(), source, place);
        }
    }
}
