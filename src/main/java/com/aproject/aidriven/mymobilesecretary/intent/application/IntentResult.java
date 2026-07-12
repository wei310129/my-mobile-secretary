package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;

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
        SCHEDULE_CONFIRMED,
        SCHEDULE_NEEDS_DECISION,
        CLARIFICATION_NEEDED,
        FALLBACK_TASK_CREATED
    }

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

    static IntentResult clarificationNeeded(String reason) {
        return new IntentResult(Action.CLARIFICATION_NEEDED, reason, null, null);
    }

    static IntentResult fallbackTaskCreated(Task task, String why) {
        return new IntentResult(Action.FALLBACK_TASK_CREATED,
                "%s,已先把原話存成任務「%s」".formatted(why, task.getTitle()), task, null);
    }
}
