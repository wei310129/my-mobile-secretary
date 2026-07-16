package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 回答已知行程與定時待辦的安排問題。
 * 這層只讀資料並解釋衝突，避免「要怎麼安排」被 LLM 誤當成新增或改期指令。
 */
@Service
public class ScheduleTaskConflictAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskService taskService;
    private final ScheduleService scheduleService;

    public ScheduleTaskConflictAnswerService(TaskService taskService, ScheduleService scheduleService) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
    }

    /** 有同句點名的行程跨過待辦期限時，回覆選項但不修改任何資料。 */
    public Optional<IntentResult> answer(String text) {
        if (!isPlanningQuestion(text)) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        return taskService.listOpenTasks().stream()
                .filter(task -> task.getDueAt() != null)
                .filter(task -> mentions(normalized, task.getTitle()))
                .flatMap(task -> scheduleService.listSchedules(null).stream()
                        .filter(this::canStillBePlanned)
                        .filter(schedule -> mentions(normalized, schedule.getTitle()))
                        .filter(schedule -> crosses(schedule, task.getDueAt()))
                        .map(schedule -> new Conflict(task, schedule)))
                .findFirst()
                .map(this::result);
    }

    static boolean isPlanningQuestion(String text) {
        String normalized = normalize(text);
        boolean asksPlan = normalized.contains("怎麼") || normalized.contains("可以嗎")
                || normalized.contains("來得及") || normalized.contains("衝突")
                || normalized.contains("撞到") || normalized.contains("撞期");
        boolean modifying = normalized.contains("改到") || normalized.contains("改成")
                || normalized.contains("取消") || normalized.contains("新增")
                || normalized.contains("建立");
        return asksPlan && !modifying;
    }

    private IntentResult result(Conflict conflict) {
        Task task = conflict.task();
        ScheduleItem schedule = conflict.schedule();
        String state = schedule.getStatus() == ScheduleStatus.PROPOSED
                ? "仍是待確認方案" : "目前已記錄為「%s」".formatted(schedule.getStatus());
        String message = "目前有時間衝突：\n"
                + "- 行程「%s」｜%s–%s｜%s\n".formatted(schedule.getTitle(),
                        format(schedule.getStartAt()), time(schedule.getEndAt()), state)
                + "- 待辦「%s」｜提醒／期限 %s\n".formatted(task.getTitle(), format(task.getDueAt()))
                + "- 原行程會跨過提醒時間。\n\n"
                + "建議可選擇的安排：\n"
                + "- 把「%s」縮短到 %s 前結束。\n".formatted(
                        schedule.getTitle(), time(task.getDueAt()))
                + "- 改到「%s」之後。\n".formatted(task.getTitle())
                + "- 若仍要照原時段，請明確回覆「仍照原時間安排」。\n\n"
                + "⚠️ 我不會自行更動「%s」的時間，也不會另建一個同名行程。"
                        .formatted(task.getTitle());
        return IntentResult.message(IntentResult.Action.SCHEDULE_NEEDS_DECISION, message);
    }

    private boolean canStillBePlanned(ScheduleItem schedule) {
        return schedule.getStatus() == ScheduleStatus.PROPOSED
                || schedule.getStatus() == ScheduleStatus.CONFIRMED
                || schedule.getStatus() == ScheduleStatus.PENDING;
    }

    private static boolean crosses(ScheduleItem schedule, Instant dueAt) {
        return !dueAt.isBefore(schedule.getStartAt()) && dueAt.isBefore(schedule.getEndAt());
    }

    private static boolean mentions(String normalizedText, String title) {
        String normalizedTitle = normalize(title);
        return !normalizedTitle.isBlank() && normalizedText.contains(normalizedTitle);
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s「」『』:：，,。！？?]", "").toLowerCase();
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static String time(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(TIME);
    }

    private record Conflict(Task task, ScheduleItem schedule) {
    }
}
