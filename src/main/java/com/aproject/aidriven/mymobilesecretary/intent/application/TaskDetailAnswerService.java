package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 確定性列出待辦明細，避免「買奶粉的明細」被誤判成價格紀錄查詢。 */
@Service
public class TaskDetailAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TaskService taskService;
    private final GeofenceRuleService geofenceRuleService;
    private final PlaceService placeService;
    private final Clock clock;

    public TaskDetailAnswerService(TaskService taskService,
                                   GeofenceRuleService geofenceRuleService,
                                   PlaceService placeService,
                                   Clock clock) {
        this.taskService = taskService;
        this.geofenceRuleService = geofenceRuleService;
        this.placeService = placeService;
        this.clock = clock;
    }

    /** 只攔截有明確待辦名稱的明細問題；「這個任務」仍交給既有對話上下文處理。 */
    public Optional<IntentResult> answer(String text) {
        if (!isTaskDetailQuestion(text)) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        return taskService.listTasks().stream()
                .filter(task -> mentions(normalized, task.getTitle()))
                .sorted(Comparator
                        .comparing((Task task) -> isOpen(task.getStatus())).reversed()
                        .thenComparing(task -> normalize(task.getTitle()).length(),
                                Comparator.reverseOrder())
                        .thenComparing(Task::getUpdatedAt, Comparator.reverseOrder()))
                .findFirst()
                .map(this::detail);
    }

    static boolean isTaskDetailQuestion(String text) {
        String compact = compact(text);
        boolean asksDetail = compact.contains("明細") || compact.contains("細項")
                || compact.contains("詳細") || compact.contains("詳情");
        boolean asksPrice = compact.contains("價格") || compact.contains("多少錢")
                || compact.contains("買過") || compact.contains("上次");
        return asksDetail && !asksPrice;
    }

    private IntentResult detail(Task task) {
        List<Place> places = geofenceRuleService.listRulesForTask(task.getId()).stream()
                .map(rule -> placeService.getPlace(rule.getPlaceId()))
                .distinct()
                .toList();
        StringBuilder message = new StringBuilder("待辦「%s」明細:".formatted(task.getTitle()));
        message.append("\n- 期限｜").append(due(task));
        message.append("\n- 優先度｜").append(priority(task));
        message.append("\n- 分類｜").append(category(task));
        message.append("\n- 狀態｜").append(status(task));
        message.append("\n- 重複｜").append(recurrence(task));
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            message.append("\n- 備註｜").append(task.getDescription());
        }
        if (places.isEmpty()) {
            message.append("\n- 地點｜尚未紀錄")
                    .append("\n\n⚠️ 目前沒有綁定地點；若這件事要到特定店家處理，請告訴我完整店名。");
        } else {
            for (Place place : places) {
                message.append("\n- 地點｜").append(place.getName());
                if (place.getAddress() != null && !place.getAddress().isBlank()) {
                    message.append("｜").append(place.getAddress());
                }
            }
        }
        return IntentResult.taskMessage(IntentResult.Action.TASK_INFO, message.toString(), task);
    }

    private String due(Task task) {
        if (task.getDueAt() == null) {
            return "尚未設定";
        }
        String timing = task.getDueAt().isBefore(Instant.now(clock)) ? "已逾期" : "尚未到";
        return "%s｜%s".formatted(format(task.getDueAt()), timing);
    }

    private static String priority(Task task) {
        return switch (task.getPriority()) {
            case HIGH -> "高";
            case NORMAL -> "一般";
            case LOW -> "低";
        };
    }

    private static String category(Task task) {
        return switch (task.getCategory()) {
            case WORK -> "工作";
            case PERSONAL -> "個人";
            case SHOPPING -> "購物";
            case HEALTH -> "健康";
            case FINANCE -> "財務";
            case OTHER -> "其他";
        };
    }

    private static String status(Task task) {
        return switch (task.getStatus()) {
            case CREATED -> "已建立";
            case SCHEDULED -> "已排程";
            case REMINDED -> "已提醒，等待回覆";
            case ESCALATED -> "已再次提醒，等待回覆";
            case CONFIRMED -> "已完成";
            case CANCELED -> "已取消";
        };
    }

    private static String recurrence(Task task) {
        return switch (task.getRecurrence()) {
            case NONE -> "單次";
            case DAILY -> "每天";
            case WEEKLY -> "每週";
            case MONTHLY -> "每月";
        };
    }

    private static boolean mentions(String normalizedText, String title) {
        String normalizedTitle = normalize(title);
        return normalizedTitle.length() >= 2 && normalizedText.contains(normalizedTitle);
    }

    private static boolean isOpen(TaskStatus status) {
        return status != TaskStatus.CONFIRMED && status != TaskStatus.CANCELED;
    }

    private static String normalize(String value) {
        return compact(value)
                .replace("列出", "")
                .replace("給我", "")
                .replace("待辦事項", "")
                .replace("待辦", "")
                .replace("任務", "")
                .replace("明細", "")
                .replace("細項訊息", "")
                .replace("詳細資訊", "")
                .replace("詳情", "")
                .replace("的", "")
                .toLowerCase();
    }

    private static String compact(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s「」『』:：，,。！？?]", "").toLowerCase();
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }
}
