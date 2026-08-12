package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Handles recent-activity queries, outcome reporting and product feedback. */
@Component
@RequiredArgsConstructor
public final class ActivityIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.LIST_RECENT,
            IntentCommand.Type.RECORD_OUTCOME,
            IntentCommand.Type.FEEDBACK);

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final ScheduleFollowUpService followUpService;
    private final ConversationContextService contextService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case LIST_RECENT -> listRecent();
            case RECORD_OUTCOME -> recordOutcome(text, command);
            case FEEDBACK -> handleFeedback(text, command);
            default -> throw new IllegalArgumentException(
                    "unsupported activity intent type " + command.type());
        };
    }

    private IntentResult listRecent() {
        record Recent(String label, Instant at) {
        }
        List<Recent> recent = java.util.stream.Stream.concat(
                        taskService.listTasks().stream().map(task -> new Recent(
                                "待辦「%s」".formatted(task.getTitle()), task.getCreatedAt())),
                        scheduleService.listSchedules(null).stream().map(item -> new Recent(
                                "行程「%s」".formatted(item.getTitle()), item.getCreatedAt())))
                .sorted(Comparator.comparing(Recent::at).reversed()).limit(5).toList();
        String message = recent.isEmpty() ? "最近沒有新增內容。"
                : "最近新增的內容:\n" + recent.stream()
                        .map(value -> "%s｜%s".formatted(value.label(), format(value.at())))
                        .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.RECENT_ACTIVITY_LISTED, message);
    }

    private IntentResult recordOutcome(String text, IntentCommand command) {
        if (command.onTime() == null) {
            throw new IllegalArgumentException("outcome missing onTime");
        }
        boolean onTime = command.onTime();
        if (!onTime && (command.overrunMinutes() == null || command.overrunMinutes() <= 0)) {
            throw new IllegalArgumentException("overrun outcome missing overrunMinutes");
        }
        Optional<ScheduleFollowUpService.OutcomeRecorded> recorded =
                followUpService.recordOutcomeForLatestAsked(
                        onTime, command.overrunMinutes(),
                        parseOutcomeReason(command.outcomeReason()), text);
        return recorded.map(IntentResult::outcomeRecorded)
                .orElseGet(() -> IntentResult.clarificationNeeded(
                        "最近沒有等待回報的行程,想回報哪一個行程的結果?"));
    }

    private IntentResult handleFeedback(String text, IntentCommand command) {
        String reason = command.reason() == null ? "" : command.reason().toUpperCase();
        if (reason.contains("DUPLICATE") || reason.contains("重複")) {
            if (!explicitDuplicateComplaint(text)) {
                return genericCorrection();
            }
            var duplicates = taskService.listOpenTasks().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            task -> task.getTitle().strip().toLowerCase()))
                    .values().stream().filter(group -> group.size() > 1).toList();
            if (duplicates.isEmpty()) {
                return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                        "我檢查了目前未完成待辦，沒有完全同名的重複項目。"
                                + "這次檢查沒有刪除或修改任何資料；如果你指的是內容相近但名稱不同，"
                                + "請告訴我那兩項名稱。");
            }
            String detail = duplicates.stream()
                    .map(group -> group.stream().map(Task::getTitle).distinct()
                            .collect(java.util.stream.Collectors.joining("／"))
                            + " × " + group.size())
                    .collect(java.util.stream.Collectors.joining("\n"));
            return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "我已確認目前有完全同名的重複候選：\n" + detail
                            + "\n\n我尚未刪除；要移除哪一項仍需你明確指定。");
        }
        if (reason.equals("MISSING_PLACE") && explicitMissingPlaceComplaint(text)) {
            Long id = contextService.taskIdAt(null);
            if (id != null) {
                Task task = taskService.getTask(id);
                return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                "你說得對,我應該接著問。『%s』要在哪裡做?".formatted(task.getTitle()));
            }
        }
        return genericCorrection();
    }

    private static boolean explicitDuplicateComplaint(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (containsAny(compact, "不是重複", "並不是重複", "不是在說重複", "並非重複")) {
            return false;
        }
        return containsAny(compact,
                "重複建立", "重複新增", "建立重複", "新增重複", "重複了", "兩個一樣");
    }

    private static boolean explicitMissingPlaceComplaint(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return containsAny(compact, "地點", "哪裡", "在哪")
                && containsAny(compact, "沒問", "沒有問", "應該問", "漏問", "沒說");
    }

    private static IntentResult genericCorrection() {
        return IntentResult.feedbackNeedsInput(
                "收到，是我理解錯了。這則回饋不會建立或修改待辦或行程，"
                        + "目前也還沒有重新處理。",
                ClarificationStep.blocking("feedback.activity-target", "repair.target",
                        "您希望我先釐清剛才回答的哪一部分？", 10));
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static OutcomeReason parseOutcomeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        try {
            return OutcomeReason.valueOf(reason.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }
}
