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

    public IntentResult {
        message = IntentReplyFormatter.format(action, message);
    }

    public enum Action {
        TASK_CREATED,
        TASK_COMPLETED,
        TASK_CANCELED,
        TASK_RESCHEDULED,
        SCHEDULE_CANCELED,
        SCHEDULE_RESCHEDULED,
        SCHEDULE_RECURRENCE_SET,
        SCHEDULE_INFO,
        PRICE_HISTORY,
        ALL_TASKS_CANCELED,
        TASKS_LISTED,
        SCHEDULES_LISTED,
        SUGGESTION_MADE,
        PLACE_INFO,
        PLACE_CREATED,
        TASK_PLACE_BOUND,
        TASK_PLACE_INFO,
        FEEDBACK_RECEIVED,
        BATCH_EXECUTED,
        SCHEDULE_CONFIRMED,
        SCHEDULE_NEEDS_DECISION,
        OUTCOME_RECORDED,
        CLARIFICATION_NEEDED,
        FALLBACK_TASK_CREATED,
        SCHEDULE_REMINDER_CREATED,
        FREE_SLOTS_SUGGESTED,
        AGENDA_LISTED,
        TASK_INFO,
        AVAILABILITY_CHECKED,
        RECENT_ACTIVITY_LISTED,
        ROUTE_SUGGESTED,
        PLACE_ALIAS_SET,
        SHOPPING_ITEMS_ADDED,
        SHOPPING_ITEM_REMOVED,
        SHOPPING_LISTED,
        INVENTORY_UPDATED,
        PRICE_COMPARISON,
        WEATHER_INFO,
        WEATHER_REMINDER_CREATED,
        TRAVEL_INFO,
        TRAFFIC_WATCH_CREATED,
        CONNECTION_CHECKED,
        PLANNING_PREFERENCE_SET,
        CONTEXT_UPDATED,
        SOCIAL_REPLIED,
        TASK_UPDATED,
        RECURRENCE_PAUSED,
        RECURRENCE_RESUMED,
        RECURRENCE_SKIPPED,
        COMPLETED_TASKS_LISTED,
        SHOPPING_ITEMS_PURCHASED,
        SHOPPING_LIST_CLEARED,
        SHOPPING_BY_PLACE_LISTED,
        AGENDA_SUMMARY,
        SCHEDULE_RESIZED,
        INVENTORY_ADJUSTED,
        INVENTORY_LISTED,
        ITEM_PLACES_INFO,
        ITEM_PLACE_BOUND,
        ITEMS_BY_PLACE_LISTED,
        SHOPPING_GROUPED_BY_PLACE,
        LOW_INVENTORY_RESTOCKED,
        REMINDER_PREFERENCE_UPDATED,
        REMINDER_PREFERENCE_INFO,
        LOCATION_TASKS_LISTED,
        PLACE_TASKS_INFO,
        TASK_GEOFENCE_INFO,
        TASK_GEOFENCE_UPDATED,
        TASK_PLACE_REMOVED,
        NEXT_SCHEDULE_INFO,
        SCHEDULE_GAP_INFO,
        SCHEDULES_GROUPED_BY_DAY,
        SCHEDULE_CONFLICTS_CHECKED,
        NEXT_TASK_SUGGESTED,
        TASKS_GROUPED_BY_CATEGORY,
        TASK_PROGRESS_INFO,
        TASKS_GROUPED_BY_DUE,
        TASK_LOAD_INFO,
        BUSIEST_TASK_DAY_INFO,
        BUSIEST_SCHEDULE_DAY_INFO,
        LONGEST_SCHEDULE_INFO,
        SCHEDULES_GROUPED_BY_PLACE,
        LAST_PURCHASE_INFO,
        PRICE_SUMMARY_INFO,
        FREQUENT_STORE_INFO,
        INVENTORY_EXTREMES_INFO,
        SHOPPING_INVENTORY_CHECKED,
        UNPLACED_ITEMS_LISTED,
        ITEM_KNOWLEDGE_SUMMARY,
        SCHEDULE_REMINDER_INFO,
        RESTAURANT_BOOKING_INFO,
        SCHEDULES_BULK_CANCELED
    }

    /** 清單訊息共用:最多列 10 筆,其餘以「…等 N 件」收尾。 */
    private static final int LIST_LIMIT = 10;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter LIST_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    static IntentResult taskCreated(Task task) {
        return new IntentResult(Action.TASK_CREATED,
                "已建立任務「%s」".formatted(task.getTitle()), task, null);
    }

    static IntentResult taskCreated(Task task, String followUp) {
        String message = "已建立任務「%s」".formatted(task.getTitle());
        if (followUp != null && !followUp.isBlank()) {
            message += "。" + followUp;
        }
        return new IntentResult(Action.TASK_CREATED, message, task, null);
    }

    /** 建立後回顯真正存下的關鍵欄位，讓使用者立即看出期限或地點是否漏抓。 */
    static IntentResult taskCreated(
            Task task,
            String followUp,
            com.aproject.aidriven.mymobilesecretary.geo.domain.Place place) {
        String due = task.getDueAt() == null ? "尚未設定"
                : ZonedDateTime.ofInstant(task.getDueAt(), TAIPEI).format(LIST_TIME);
        String priority = switch (task.getPriority()) {
            case HIGH -> "高";
            case NORMAL -> "一般";
            case LOW -> "低";
        };
        String location = place == null ? "尚未綁定" : place.getName();
        StringBuilder message = new StringBuilder("已建立任務「%s」:".formatted(task.getTitle()))
                .append("\n- 期限｜").append(due)
                .append("\n- 優先度｜").append(priority)
                .append("\n- 地點｜").append(location);
        if (followUp != null && !followUp.isBlank()) {
            message.append("\n\n").append(followUp);
        }
        return new IntentResult(Action.TASK_CREATED, message.toString(), task, null);
    }

    static IntentResult message(Action action, String message) {
        return new IntentResult(action, message, null, null);
    }

    static IntentResult taskMessage(Action action, String message, Task task) {
        return new IntentResult(action, message, task, null);
    }

    static IntentResult scheduleMessage(Action action, String message, ScheduleDecision decision) {
        return new IntentResult(action, message, null, decision);
    }

    static IntentResult scheduleDecided(ScheduleDecision decision) {
        boolean feasible = decision.feasibility().feasible();
        String recurrenceDetails = scheduleRecurrenceDetails(decision.item());
        boolean nested = decision.feasibility().issues().stream()
                .anyMatch(issue -> issue.type()
                        == com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue.Type
                        .NESTED_IN_RECURRING_SCHEDULE);
        boolean crossesTask = decision.feasibility().issues().stream()
                .anyMatch(issue -> issue.type()
                        == com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue.Type
                        .TASK_DUE_DURING_SCHEDULE);
        String issues = decision.feasibility().issues().stream()
                .map(issue -> "- " + issue.message())
                .collect(Collectors.joining("\n"));
        return new IntentResult(
                feasible ? Action.SCHEDULE_CONFIRMED : Action.SCHEDULE_NEEDS_DECISION,
                feasible
                        ? "行程「%s」可行,已確認%s".formatted(
                                decision.item().getTitle(), recurrenceDetails)
                        : nested
                        ? "行程「%s」尚未確認：%s\n%s\n\n請回覆是否併入固定行程；我不會自行確認或要求改期。"
                                .formatted(decision.item().getTitle(), recurrenceDetails, issues)
                        : crossesTask
                        ? "行程「%s」尚未確認：%s\n%s\n\n請確認要縮短、延後，或仍照原時間安排；我不會自行更動待辦或行程。"
                                .formatted(decision.item().getTitle(), recurrenceDetails, issues)
                        : "行程「%s」尚未確認：%s\n%s\n\n請告訴我要改哪個行程或指定新時間；我不會自行確認。"
                                .formatted(decision.item().getTitle(), recurrenceDetails, issues),
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
            // 格式依使用者 2026-07-15 範例:編號後不空格
            message.append("\n%d.「%s」%s(%s)".formatted(i + 1, task.getTitle(), urgency, due));
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

    static IntentResult taskCanceled(Task task) {
        return new IntentResult(Action.TASK_CANCELED,
                "「%s」已取消,不再追蹤提醒".formatted(task.getTitle()), task, null);
    }

    static IntentResult taskRescheduled(Task task) {
        return new IntentResult(Action.TASK_RESCHEDULED,
                "「%s」期限改到 %s".formatted(task.getTitle(),
                        ZonedDateTime.ofInstant(task.getDueAt(), TAIPEI).format(LIST_TIME)),
                task, null);
    }

    static IntentResult scheduleRecurrenceSet(ScheduleItem item) {
        String label = switch (item.getRecurrence()) {
            case WEEKLY -> "每週固定";
            case WEEKDAYS -> "每個上班日固定";
            case NONE -> null;
        };
        return new IntentResult(Action.SCHEDULE_RECURRENCE_SET,
                label != null
                        ? "「%s」已設為%s%s,之後會自動排下一場。".formatted(
                                item.getTitle(), label, recurrenceUntilLabel(item))
                        : "「%s」已改回單次行程,不再自動重複。".formatted(item.getTitle()),
                null, null);
    }

    static IntentResult scheduleInfo(ScheduleItem item,
                                     com.aproject.aidriven.mymobilesecretary.geo.domain.Place place) {
        String time = "%s-%s".formatted(
                ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(LIST_TIME),
                ZonedDateTime.ofInstant(item.getEndAt(), TAIPEI)
                        .format(DateTimeFormatter.ofPattern("HH:mm")));
        StringBuilder message = new StringBuilder("「%s」:%s".formatted(item.getTitle(), time));
        message.append("\n類型:").append(switch (item.getRecurrence()) {
            case WEEKLY -> "每週固定";
            case WEEKDAYS -> "每個上班日固定";
            case NONE -> item.getRecurrenceUntil() == null ? "單次" : "固定規則已結束";
        });
        if (item.getRecurrenceUntil() != null) {
            message.append("\n截止:").append(item.getRecurrenceUntil()
                    .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))).append("（含當日）");
        }
        if (place != null) {
            message.append("\n地點:").append(place.getName());
        }
        return new IntentResult(Action.SCHEDULE_INFO, message.toString(), null, null);
    }

    private static String scheduleRecurrenceDetails(ScheduleItem item) {
        String label = switch (item.getRecurrence()) {
            case WEEKLY -> "每週固定";
            case WEEKDAYS -> "每個上班日固定";
            case NONE -> null;
        };
        if (label == null) {
            return "";
        }
        return "\n固定規則:" + label + recurrenceUntilLabel(item);
    }

    private static String recurrenceUntilLabel(ScheduleItem item) {
        return item.getRecurrenceUntil() == null ? ""
                : "至%s（含當日）".formatted(item.getRecurrenceUntil()
                        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd")));
    }

    static IntentResult priceHistory(String keyword,
                                     List<com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord> records) {
        if (records.isEmpty()) {
            return new IntentResult(Action.PRICE_HISTORY,
                    "沒有「%s」的價格紀錄;傳收據照片給我,之後就查得到。".formatted(keyword), null, null);
        }
        String lines = records.stream().limit(LIST_LIMIT)
                .map(r -> "%s %s %d元%s".formatted(
                        r.getPurchasedAt().format(DateTimeFormatter.ofPattern("MM/dd")),
                        r.getStoreName() == null || r.getStoreName().isBlank() ? "" : r.getStoreName(),
                        r.getPriceTwd(),
                        "「" + r.getItemName() + "」"))
                .collect(Collectors.joining("\n"));
        String tail = records.size() > LIST_LIMIT ? "\n…等 %d 筆".formatted(records.size()) : "";
        return new IntentResult(Action.PRICE_HISTORY,
                "「%s」的購買明細(%d 筆):\n%s%s".formatted(keyword, records.size(), lines, tail),
                null, null);
    }

    static IntentResult scheduleCanceled(ScheduleItem item) {
        return new IntentResult(Action.SCHEDULE_CANCELED,
                "行程「%s」已取消".formatted(item.getTitle()), null, null);
    }

    static IntentResult scheduleRescheduled(ScheduleDecision decision) {
        ScheduleItem item = decision.item();
        String interval = "%s-%s".formatted(
                ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(LIST_TIME),
                ZonedDateTime.ofInstant(item.getEndAt(), TAIPEI)
                        .format(DateTimeFormatter.ofPattern("HH:mm")));
        String message = decision.feasibility().feasible()
                ? "行程「%s」已改到 %s,可行並已確認".formatted(item.getTitle(), interval)
                : "行程「%s」已改到 %s,但新時段有問題,需要你決定".formatted(item.getTitle(), interval);
        return new IntentResult(Action.SCHEDULE_RESCHEDULED, message, null, decision);
    }

    static IntentResult allTasksCanceled(List<Task> canceled) {
        if (canceled.isEmpty()) {
            return new IntentResult(Action.ALL_TASKS_CANCELED, "目前沒有可取消的待辦。", null, null);
        }
        String titles = canceled.stream().limit(LIST_LIMIT)
                .map(t -> "「" + t.getTitle() + "」")
                .collect(Collectors.joining("\n"));
        return new IntentResult(Action.ALL_TASKS_CANCELED,
                "已取消全部 %d 件待辦:\n%s".formatted(canceled.size(), titles), null, null);
    }

    static IntentResult placeCreated(com.aproject.aidriven.mymobilesecretary.geo.domain.Place place) {
        String detail = place.getAddress() == null || place.getAddress().isBlank()
                ? "座標 (%.5f, %.5f)".formatted(place.getLatitude(), place.getLongitude())
                : place.getAddress();
        return new IntentResult(Action.PLACE_CREATED,
                "已建立地點「%s」:%s%s".formatted(place.getName(), detail,
                        place.getType() == null || place.getType().isBlank()
                                ? "" : "(%s)".formatted(place.getType())),
                null, null);
    }

    static IntentResult taskPlaceBound(Task task,
                                       com.aproject.aidriven.mymobilesecretary.geo.domain.Place place) {
        return new IntentResult(Action.TASK_PLACE_BOUND,
                "「%s」已綁定「%s」,你到附近時我會提醒你。".formatted(task.getTitle(), place.getName()),
                task, null);
    }

    static IntentResult taskPlaceInfo(Task task,
                                      List<com.aproject.aidriven.mymobilesecretary.geo.domain.Place> places) {
        if (places.isEmpty()) {
            return new IntentResult(Action.TASK_PLACE_INFO,
                    "「%s」還沒綁定地點,跟我說「%s是要到某地點」我就記住。"
                            .formatted(task.getTitle(), task.getTitle()),
                    task, null);
        }
        String lines = places.stream()
                .map(p -> "「%s」%s".formatted(p.getName(),
                        p.getAddress() == null || p.getAddress().isBlank() ? "" : ":" + p.getAddress()))
                .collect(Collectors.joining("\n"));
        return new IntentResult(Action.TASK_PLACE_INFO,
                "「%s」要去:\n%s".formatted(task.getTitle(), lines), task, null);
    }

    static IntentResult feedbackReceived() {
        return new IntentResult(Action.FEEDBACK_RECEIVED,
                "收到,這個問題我記下來了,下次開發會處理。", null, null);
    }

    static IntentResult placeInfo(com.aproject.aidriven.mymobilesecretary.geo.domain.Place place) {
        String location = place.getAddress() == null || place.getAddress().isBlank()
                ? "座標 (%.5f, %.5f)".formatted(place.getLatitude(), place.getLongitude())
                : place.getAddress();
        String type = place.getType() == null || place.getType().isBlank()
                ? "" : "(%s)".formatted(place.getType());
        return new IntentResult(Action.PLACE_INFO,
                "「%s」%s:%s".formatted(place.getName(), type, location), null, null);
    }

    /** 一句多操作的合併回覆:逐項列出各自結果。 */
    static IntentResult batchExecuted(List<String> lines) {
        StringBuilder message = new StringBuilder("一次處理 %d 件:".formatted(lines.size()));
        for (int i = 0; i < lines.size(); i++) {
            message.append("\n%d.%s".formatted(i + 1, lines.get(i)));
        }
        return new IntentResult(Action.BATCH_EXECUTED, message.toString(), null, null);
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
