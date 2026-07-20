package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderPreferenceService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderPreference;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Executes reminder creation and user-controlled reminder preference changes. */
@Component
@RequiredArgsConstructor
public final class ReminderIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.ADD_SCHEDULE_REMINDER,
            IntentCommand.Type.ASK_SCHEDULE_REMINDER,
            IntentCommand.Type.SET_QUIET_HOURS,
            IntentCommand.Type.CLEAR_QUIET_HOURS,
            IntentCommand.Type.MUTE_REMINDERS,
            IntentCommand.Type.RESUME_REMINDERS,
            IntentCommand.Type.ASK_REMINDER_PREFERENCES);

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final ConversationContextService contextService;
    private final ReminderPreferenceService reminderPreferenceService;
    private final Clock clock;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        try {
            IntentOptions options = command.safeOptions();
            return switch (command.type()) {
                case ADD_SCHEDULE_REMINDER -> addScheduleReminder(command, options);
                case ASK_SCHEDULE_REMINDER -> askScheduleReminder(command, options);
                case SET_QUIET_HOURS -> setQuietHours(options);
                case CLEAR_QUIET_HOURS -> clearQuietHours();
                case MUTE_REMINDERS -> muteReminders(command);
                case RESUME_REMINDERS -> resumeReminders();
                case ASK_REMINDER_PREFERENCES -> reminderPreferences();
                default -> throw new IllegalArgumentException(
                        "unsupported reminder intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult addScheduleReminder(IntentCommand command, IntentOptions options) {
        ScheduleItem item = scheduleTarget(command, options);
        int lead = positive(options.leadMinutes(), 10);
        Instant due = item.getStartAt().minus(Duration.ofMinutes(lead));
        Task task = taskService.createTask("提醒:" + item.getTitle(),
                "行程開始前 %d 分鐘".formatted(lead), TaskPriority.NORMAL, due,
                Task.Category.OTHER, Task.Recurrence.NONE, Task.ConditionType.NONE, null);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.SCHEDULE_REMINDER_CREATED,
                "已設定「%s」開始前 %d 分鐘提醒(%s)。".formatted(
                        item.getTitle(), lead, format(due)));
    }

    private IntentResult askScheduleReminder(IntentCommand command, IntentOptions options) {
        ScheduleItem item = scheduleTarget(command, options);
        String reminderTitle = "提醒:" + item.getTitle();
        List<Task> reminders = taskService.listOpenTasks().stream()
                .filter(task -> task.getTitle().equalsIgnoreCase(reminderTitle))
                .filter(task -> task.getDueAt() != null)
                .toList();
        if (reminders.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULE_REMINDER_INFO,
                    "「%s」是 %s–%s，目前沒有設定行程提醒。你可以說「開始前 10 分鐘提醒我」；我不會自己加。"
                            .formatted(item.getTitle(), format(item.getStartAt()), time(item.getEndAt())));
        }
        String times = reminders.stream().map(task -> format(task.getDueAt()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULE_REMINDER_INFO,
                "「%s」是 %s–%s。\n\n目前的提醒時間:\n%s".formatted(
                        item.getTitle(), format(item.getStartAt()), time(item.getEndAt()), times));
    }

    private IntentResult setQuietHours(IntentOptions options) {
        require(options.quietStart(), "quietStart");
        require(options.quietEnd(), "quietEnd");
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            start = java.time.LocalTime.parse(options.quietStart());
            end = java.time.LocalTime.parse(options.quietEnd());
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad quiet hours");
        }
        boolean allowHigh = options.allowHighPriority() == null || options.allowHighPriority();
        reminderPreferenceService.setQuietHours(start, end, allowHigh);
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已設定每日 %s–%s 勿擾;%s。".formatted(start, end,
                        allowHigh ? "緊急待辦仍會提醒" : "緊急待辦也會延後"));
    }

    private IntentResult clearQuietHours() {
        reminderPreferenceService.clearQuietHours();
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已取消固定勿擾時段。 ");
    }

    private IntentResult muteReminders(IntentCommand command) {
        Instant until = requiredTime(command.dueAt(), "dueAt");
        if (!until.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("mute time must be future");
        }
        ReminderPreference preference = reminderPreferenceService.muteUntil(until);
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "一般提醒已暫停到 %s;到時會自動恢復%s。".formatted(format(until),
                        preference.isAllowHighPriority() ? "，緊急待辦仍會提醒" : ""));
    }

    private IntentResult resumeReminders() {
        reminderPreferenceService.resumeNow();
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已取消臨時靜音;固定勿擾時段仍照原設定。 ");
    }

    private IntentResult reminderPreferences() {
        Optional<ReminderPreference> found = reminderPreferenceService.preference();
        if (found.isEmpty()) {
            return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_INFO,
                    "目前沒有固定勿擾或臨時靜音設定。 ");
        }
        ReminderPreference preference = found.get();
        String quiet = preference.getQuietStart() == null ? "無固定勿擾"
                : "每日 %s–%s 勿擾(%s緊急提醒)".formatted(
                        preference.getQuietStart(), preference.getQuietEnd(),
                        preference.isAllowHighPriority() ? "保留" : "包含");
        String mute = preference.getMutedUntil() != null
                        && preference.getMutedUntil().isAfter(Instant.now(clock))
                ? "，臨時靜音到 " + format(preference.getMutedUntil()) : "";
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_INFO, quiet + mute + "。");
    }

    private ScheduleItem scheduleTarget(IntentCommand command, IntentOptions options) {
        String title = command.title();
        if ((title == null || title.isBlank()) && options.referenceTitle() != null) {
            title = options.referenceTitle();
        }
        if (title != null && !title.isBlank()) {
            List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
            if (matches.size() != 1) {
                throw new IllegalArgumentException("schedule target is not unique");
            }
            return matches.getFirst();
        }
        Long id = contextService.scheduleIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("schedule context missing");
        }
        return scheduleService.getSchedule(id);
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static Instant requiredTime(String value, String field) {
        Instant parsed = parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return parsed;
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad time: " + value);
        }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static String time(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
