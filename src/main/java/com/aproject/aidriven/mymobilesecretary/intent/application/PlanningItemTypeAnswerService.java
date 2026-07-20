package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.planning.application.PlanningItemClassifier;
import com.aproject.aidriven.mymobilesecretary.planning.domain.PlanningItemType;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 確定性回答「這筆到底是草稿、待辦事項、行程提醒還是行程」。 */
@Service
public class PlanningItemTypeAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TaskService taskService;
    private final ScheduleService scheduleService;

    public PlanningItemTypeAnswerService(TaskService taskService, ScheduleService scheduleService) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
    }

    public Optional<IntentResult> answer(String userText, String interpretationText) {
        if (!asksForType(userText)) return Optional.empty();

        String context = normalize((interpretationText == null ? "" : interpretationText)
                + " " + userText);
        List<Row> rows = new ArrayList<>();
        for (Task task : taskService.listOpenTasks()) {
            if (mentions(context, task.getTitle())) {
                PlanningItemType type = PlanningItemClassifier.classify(task);
                String timing = task.getDueAt() == null
                        ? "沒有執行日期或時間"
                        : "提醒時間 " + DATE_TIME.format(task.getDueAt().atZone(TAIPEI));
                rows.add(new Row(type, task.getTitle(), timing, task.getId()));
            }
        }
        for (ScheduleItem item : scheduleService.listSchedules(null)) {
            if (canShow(item) && mentions(context, item.getTitle())) {
                PlanningItemType type = PlanningItemClassifier.classify(item);
                String timing = "%s–%s".formatted(
                        DATE_TIME.format(item.getStartAt().atZone(TAIPEI)),
                        DateTimeFormatter.ofPattern("HH:mm").format(item.getEndAt().atZone(TAIPEI)));
                rows.add(new Row(type, item.getTitle(), timing, item.getId()));
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.type().ordinal())
                .thenComparing(Row::title)
                .thenComparing(Row::id, Comparator.nullsLast(Comparator.naturalOrder())));

        if (rows.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "這句沒有唯一指到一筆資料，所以我不會拿目前未完成的草稿代答。\n"
                            + typeLegend()
                            + "\n\n請用 LINE 回覆原清單，或告訴我清單中的編號／名稱，我會按實際類別列出。"));
        }

        StringBuilder message = new StringBuilder("📋 找到的項目按實際類別列出：");
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            message.append("\n").append(index + 1).append(". ")
                    .append(row.type().displayName()).append("「")
                    .append(row.title()).append("」｜").append(row.timing());
        }
        message.append("\n\n").append(typeLegend());
        return Optional.of(IntentResult.message(IntentResult.Action.TASK_INFO, message.toString()));
    }

    static boolean asksForType(String text) {
        String value = normalize(text);
        boolean hasType = containsAny(value, "草稿", "待辦", "提醒事項", "提醒紀錄",
                "行程提醒", "知識紀錄", "行程");
        boolean compares = containsAny(value, "是什麼", "是哪一", "是草稿嗎", "還是",
                "類別", "分類", "沒講清楚", "不同類別");
        return hasType && compares;
    }

    private static String typeLegend() {
        return "類別規則：草稿是暫存；待辦事項持久化但沒有執行時間；"
                + "行程提醒有日曆時點但不占用時段、不參與撞期；"
                + "行程才代表本人必須到場或上線，並接受撞期與可行性檢查。";
    }

    private static boolean canShow(ScheduleItem item) {
        return item.getStatus() == ScheduleStatus.PROPOSED
                || item.getStatus() == ScheduleStatus.PENDING
                || item.getStatus() == ScheduleStatus.CONFIRMED;
    }

    private static boolean mentions(String text, String title) {
        String normalizedTitle = normalize(title);
        return normalizedTitle.length() >= 2 && text.contains(normalizedTitle);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s「」『』:：，,。！？?]", "").toLowerCase();
    }

    private record Row(PlanningItemType type, String title, String timing, Long id) {
    }
}
