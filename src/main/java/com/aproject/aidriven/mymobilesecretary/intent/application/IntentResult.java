package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService.OutcomeRecorded;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意圖處理的結果:做了什麼 + 相關物件。
 *
 * @param action   結果種類(給客戶端分支)
 * @param message  人類可讀說明(表達層之前的簡易版)
 * @param task     建立的任務(TASK 類結果)
 * @param decision 行程決策(SCHEDULE 類結果,含可行性)
 */
public record IntentResult(
        Action action,
        String message,
        Task task,
        ScheduleDecision decision
) {

    public enum Action {
        TASK_CREATED,
        TASK_COMPLETED,
        TASKS_LISTED,
        SCHEDULES_LISTED,
        SUGGESTION_MADE,
        SCHEDULE_CONFIRMED,
        SCHEDULE_NEEDS_DECISION,
        OUTCOME_RECORDED,
        CLARIFICATION_NEEDED,
        FALLBACK_TASK_CREATED
    }

    /** 清單訊息共用:最多列 10 筆,其餘以「…等 N 件」收尾。 */
    private static final int LIST_LIMIT = 10;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter LIST_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    static IntentResult taskCreated(Task task) {
        return new IntentResult(Action.TASK_CREATED,
                "已建立任務「%s」".formatted(task.getTitle()), task, null);
    }

    static IntentResult scheduleDecided(ScheduleDecision decision) {
        boolean feasible = decision.feasibility().feasible();
        return new IntentResult(
                feasible ? Action.SCHEDULE_CONFIRMED : Action.SCHEDULE_NEEDS_DECISION,
                feasible
                        ? "行程「%s」可行,已確認".formatted(decision.item().getTitle())
                        : "行程「%s」有問題,需要你決定".formatted(decision.item().getTitle()),
                null, decision);
    }

    static IntentResult tasksListed(List<Task> tasks, String advice) {
        if (tasks.isEmpty()) {
            return new IntentResult(Action.TASKS_LISTED, "目前沒有未完成的待辦,都清光了。", null, null);
        }
        // 一行一項(使用者要求分列):期限、緊急程度標記各自成欄
        StringBuilder message = new StringBuilder("還有 %d 件待辦:".formatted(tasks.size()));
        List<Task> shown = tasks.stream().limit(LIST_LIMIT).toList();
        for (int i = 0; i < shown.size(); i++) {
            Task task = shown.get(i);
            String due = task.getDueAt() == null
                    ? "無期限"
                    : "期限 " + ZonedDateTime.ofInstant(task.getDueAt(), TAIPEI).format(LIST_TIME);
            String urgency = switch (task.getPriority()) {
                case HIGH -> "【急】";
                case LOW -> "【不急】";
                default -> "";
            };
            message.append("\n%d. 「%s」%s(%s)".formatted(i + 1, task.getTitle(), urgency, due));
        }
        if (tasks.size() > LIST_LIMIT) {
            message.append("\n…等 %d 件".formatted(tasks.size()));
        }
        if (advice != null && !advice.isBlank()) {
            message.append(advice);
        }
        return new IntentResult(Action.TASKS_LISTED, message.toString(), null, null);
    }

    static IntentResult schedulesListed(List<ScheduleItem> items) {
        if (items.isEmpty()) {
            return new IntentResult(Action.SCHEDULES_LISTED, "接下來沒有已確認的行程。", null, null);
        }
        String lines = items.stream().limit(LIST_LIMIT)
                .map(item -> "「%s」%s-%s".formatted(item.getTitle(),
                        ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(LIST_TIME),
                        ZonedDateTime.ofInstant(item.getEndAt(), TAIPEI)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))))
                .collect(Collectors.joining("\n"));
        String tail = items.size() > LIST_LIMIT ? "\n…等 %d 個".formatted(items.size()) : "";
        return new IntentResult(Action.SCHEDULES_LISTED,
                "接下來 %d 個行程:\n%s%s".formatted(items.size(), lines, tail), null, null);
    }

    static IntentResult suggestionMade(String message) {
        return new IntentResult(Action.SUGGESTION_MADE, message, null, null);
    }

    static IntentResult taskCompleted(Task task) {
        return new IntentResult(Action.TASK_COMPLETED,
                "「%s」完成,已從追蹤清單劃掉".formatted(task.getTitle()), task, null);
    }

    static IntentResult outcomeRecorded(OutcomeRecorded recorded) {
        String detail = recorded.outcome().isOnTime()
                ? "準時完成"
                : "超時 %d 分鐘".formatted(recorded.outcome().getOverrunMinutes());
        return new IntentResult(Action.OUTCOME_RECORDED,
                "已記下「%s」的結果:%s".formatted(recorded.item().getTitle(), detail), null, null);
    }

    static IntentResult clarificationNeeded(String reason) {
        return new IntentResult(Action.CLARIFICATION_NEEDED, reason, null, null);
    }

    static IntentResult fallbackTaskCreated(Task task, String why) {
        return new IntentResult(Action.FALLBACK_TASK_CREATED,
                "%s,已先把原話存成任務「%s」".formatted(why, task.getTitle()), task, null);
    }
}
