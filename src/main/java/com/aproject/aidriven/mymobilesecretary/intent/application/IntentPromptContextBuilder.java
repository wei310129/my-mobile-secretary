package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import com.aproject.aidriven.mymobilesecretary.shared.security.PromptInjectionGuard;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds only the state relevant to the current sentence. This keeps unrelated personal data out
 * of the model context and prevents prompt size from growing linearly with the user's database.
 */
@Component
@RequiredArgsConstructor
public final class IntentPromptContextBuilder {

    static final int MAX_STATE_ROWS = 30;
    static final int MAX_CONTEXT_CHARACTERS = 2_000;
    static final int MAX_USER_MESSAGE_CHARACTERS = 6_000;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final PlaceRepository placeRepository;
    private final TaskRepository taskRepository;
    private final ScheduleItemRepository scheduleRepository;
    private final ItemRepository itemRepository;
    private final ReminderPreferenceRepository reminderPreferenceRepository;

    public String build(String text, Instant now, ConversationSnapshot context) {
        Selection selection = Selection.forMessage(text);
        StringBuilder prompt = new StringBuilder(2_000);
        prompt.append("現在時間(台北):")
                .append(ZonedDateTime.ofInstant(now, TAIPEI)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")))
                .append('\n');

        if (selection.places()) {
            append(prompt, "known_places", knownPlaces());
        }
        if (selection.tasks()) {
            append(prompt, "open_tasks", openTasks());
        }
        if (selection.schedules()) {
            append(prompt, "schedules", schedules());
        }
        if (selection.items()) {
            append(prompt, "item_state", itemState());
        }
        if (selection.reminderPreference()) {
            append(prompt, "reminder_preference", reminderPreference());
        }
        if (selection.shortTermContext()) {
            append(prompt, "short_term_context",
                    bounded(String.valueOf(context), MAX_CONTEXT_CHARACTERS));
        }
        prompt.append('\n');
        append(prompt, "current_user_message", bounded(text, MAX_USER_MESSAGE_CHARACTERS));
        return prompt.toString().stripTrailing();
    }

    private String knownPlaces() {
        return placeRepository.findAll().stream()
                .limit(MAX_STATE_ROWS)
                .map(Place::getName)
                .collect(Collectors.joining("、"));
    }

    private String openTasks() {
        return taskRepository.findByStatusIn(EnumSet.of(
                        TaskStatus.CREATED, TaskStatus.SCHEDULED,
                        TaskStatus.REMINDED, TaskStatus.ESCALATED))
                .stream()
                .limit(MAX_STATE_ROWS)
                .map(task -> "%d:%s%s".formatted(task.getId(), task.getTitle(),
                        task.getDueAt() == null ? "" : "@" + task.getDueAt()))
                .collect(Collectors.joining("、"));
    }

    private String schedules() {
        var items = scheduleRepository.findByStatusInOrderByStartAtAsc(EnumSet.of(
                        ScheduleStatus.PROPOSED, ScheduleStatus.CONFIRMED,
                        ScheduleStatus.PENDING)).stream()
                .limit(MAX_STATE_ROWS).toList();
        var placeIds = items.stream().map(item -> item.getPlaceId())
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Place> places = placeIds.isEmpty()
                ? Map.of()
                : StreamSupport.stream(placeRepository.findAllById(placeIds).spliterator(), false)
                        .collect(Collectors.toMap(Place::getId, Function.identity()));
        return items.stream()
                .map(item -> "%d:%s@start=%s;end=%s;recurrence=%s%s;place=%s".formatted(
                        item.getId(), item.getTitle(), item.getStartAt(), item.getEndAt(),
                        item.getRecurrence(), item.getRecurrenceUntil() == null
                                ? "" : ";until=" + item.getRecurrenceUntil(),
                        item.getPlaceId() == null ? "(未設定)"
                                : java.util.Optional.ofNullable(places.get(item.getPlaceId()))
                                        .map(Place::getName).orElse("id:" + item.getPlaceId())))
                .collect(Collectors.joining("、"));
    }

    private String itemState() {
        return itemRepository.findAll().stream()
                .filter(item -> item.isShoppingNeeded() || item.getInventoryQuantity() > 0)
                .limit(MAX_STATE_ROWS)
                .map(item -> "%s(庫存%d%s)".formatted(
                        item.getName(), item.getInventoryQuantity(),
                        item.isShoppingNeeded() ? ",待買" : ""))
                .collect(Collectors.joining("、"));
    }

    private String reminderPreference() {
        return reminderPreferenceRepository.findFirstByOrderByIdAsc()
                .map(preference -> "勿擾=%s-%s,緊急例外=%s,靜音到=%s".formatted(
                        preference.getQuietStart(), preference.getQuietEnd(),
                        preference.isAllowHighPriority(), preference.getMutedUntil()))
                .orElse("(無)");
    }

    private static void append(StringBuilder prompt, String label, String value) {
        prompt.append(PromptInjectionGuard.delimit(
                label, value == null || value.isBlank() ? "(無)" : value)).append('\n');
    }

    private static String bounded(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value;
        }
        return value.substring(0, maxCharacters) + "…[truncated]";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    record Selection(boolean places, boolean tasks, boolean schedules, boolean items,
                     boolean reminderPreference, boolean shortTermContext) {

        static Selection forMessage(String text) {
            String value = text == null ? "" : text.replaceAll("\\s+", "");
            boolean continuation = containsAny(value,
                    "上一個", "上一筆", "剛剛", "剛才", "那個", "那件", "第二個",
                    "第三個", "同一個", "一樣", "它", "他", "她", "繼續", "確認");
            boolean modification = containsAny(value,
                    "取消", "刪掉", "不要了", "完成", "做完", "改到", "改成", "延期",
                    "提前", "延後", "暫停", "恢復", "跳過", "不用", "買到了", "繳完");
            boolean agenda = containsAny(value,
                    "今天有什麼", "明天有什麼", "接下來", "待會", "最近", "全部");
            boolean tasks = agenda || modification || containsAny(value,
                    "待辦", "任務", "要做", "還有什麼事", "優先", "最急", "進度",
                    "工作量", "件事", "繳費", "聯絡", "包裹", "提醒我");
            boolean schedules = agenda || modification || containsAny(value,
                    "行程", "活動", "會議", "上課", "下課", "接送", "有空", "空檔",
                    "衝突", "幾點", "改期", "固定行程", "週會");
            boolean items = containsAny(value,
                    "買", "購物", "庫存", "價格", "多少錢", "品項", "用完", "補貨",
                    "清單", "哪家店");
            boolean places = containsAny(value,
                    "地點", "哪裡", "哪間", "地址", "附近", "分店", "學校", "幼兒園",
                    "公司", "上班地點", "店到店", "入口", "後門", "路線", "接送", "在哪");
            boolean reminder = containsAny(value,
                    "提醒", "通知", "勿擾", "靜音", "不要吵", "幾分鐘前");
            return new Selection(places, tasks, schedules, items, reminder, continuation);
        }
    }
}
