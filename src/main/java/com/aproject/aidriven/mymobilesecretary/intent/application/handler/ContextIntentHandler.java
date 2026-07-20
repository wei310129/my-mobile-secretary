package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Handles commands that explicitly act on the user's remembered task or schedule context. */
@Component
@RequiredArgsConstructor
public final class ContextIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.ACCEPT_CONTEXT,
            IntentCommand.Type.CANCEL_CONTEXT,
            IntentCommand.Type.COPY_CONTEXT,
            IntentCommand.Type.SET_CONTEXT_PLACE,
            IntentCommand.Type.SHIFT_CONTEXT_LATER);

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final ConversationContextService contextService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;
    private final GeofenceRuleService geofenceService;
    private final BulkScheduleCancellationService bulkScheduleCancellationService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        try {
            IntentOptions options = command.safeOptions();
            return switch (command.type()) {
                case ACCEPT_CONTEXT -> acceptContext();
                case SHIFT_CONTEXT_LATER -> shiftContext(command, options);
                case CANCEL_CONTEXT -> cancelContext(options);
                case SET_CONTEXT_PLACE -> setContextPlace(command, options);
                case COPY_CONTEXT -> copyContext(command, options);
                default -> throw new IllegalArgumentException(
                        "unsupported context intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult acceptContext() {
        var snapshot = contextService.snapshot();
        if (IntentResult.Action.SCHEDULE_CANCELLATION_PREVIEWED.name()
                .equals(snapshot.lastAction())) {
            return bulkScheduleCancellationService.confirmPreview(
                    snapshot.lastScheduleListIds());
        }
        Long id = contextService.scheduleIdAt(null);
        if (id == null) {
            return IntentResult.clarificationNeeded("目前沒有可接受的行程提案。");
        }
        ScheduleItem item = scheduleService.getSchedule(id);
        if (item.getStatus() == ScheduleStatus.PROPOSED) {
            scheduleService.confirmSchedule(id);
        }
        contextService.rememberSchedule(item);
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "好,已確認行程「%s」。".formatted(item.getTitle()));
    }

    private IntentResult shiftContext(IntentCommand command, IntentOptions options) {
        int minutes = positive(options.shiftMinutes(), 60);
        if ("TASK".equalsIgnoreCase(options.referenceKind())) {
            Task task = taskTarget(command, options);
            if (task.getDueAt() == null) {
                return IntentResult.clarificationNeeded("這件待辦目前沒有時間,請直接說要改到何時。");
            }
            Task changed = taskService.changeDueDate(
                    task.getId(), task.getDueAt().plus(Duration.ofMinutes(minutes)));
            contextService.rememberTask(changed);
            return IntentResult.taskRescheduled(changed);
        }
        ScheduleItem item = scheduleTarget(command, options);
        var decision = scheduleService.reschedule(item.getId(),
                item.getStartAt().plus(Duration.ofMinutes(minutes)),
                item.getEndAt().plus(Duration.ofMinutes(minutes)));
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleRescheduled(decision);
    }

    private IntentResult cancelContext(IntentOptions options) {
        if ("TASK".equalsIgnoreCase(options.referenceKind())) {
            Long id = contextService.taskIdAt(options.ordinal());
            if (id == null) {
                return IntentResult.clarificationNeeded("目前沒有可取消的待辦。");
            }
            Task task = taskService.cancelTask(id);
            contextService.rememberTask(task);
            return IntentResult.taskCanceled(task);
        }
        Long id = contextService.scheduleIdAt(options.ordinal());
        if (id == null) {
            return IntentResult.clarificationNeeded("目前沒有可放棄的行程提案。");
        }
        ScheduleItem item = scheduleService.getSchedule(id);
        if (item.getStatus() == ScheduleStatus.PROPOSED) {
            scheduleService.rejectSchedule(id);
        } else {
            scheduleService.cancelSchedule(id);
        }
        contextService.rememberSchedule(item);
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "已放棄「%s」。".formatted(item.getTitle()));
    }

    private IntentResult setContextPlace(IntentCommand command, IntentOptions options) {
        Place place = placeAliasService.resolve(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        if ("SCHEDULE".equalsIgnoreCase(options.referenceKind())) {
            Long id = contextService.scheduleIdAt(options.ordinal());
            if (id == null) {
                return IntentResult.clarificationNeeded("目前沒有可修改的行程。");
            }
            var decision = scheduleService.changePlace(id, place.getId());
            contextService.rememberSchedule(decision.item());
            return IntentResult.scheduleRescheduled(decision);
        }
        Long id = contextService.taskIdAt(options.ordinal());
        if (id == null) {
            return IntentResult.clarificationNeeded("目前沒有可綁定地點的待辦。");
        }
        TriggerType trigger = parseTrigger(options.triggerType());
        if (!geofenceService.ruleExists(id, place.getId(), trigger)) {
            geofenceService.createRule(
                    id, place.getId(), positive(options.radiusMeters(), 200), trigger);
        }
        Task task = taskService.getTask(id);
        contextService.rememberTask(task);
        return IntentResult.taskPlaceBound(task, place);
    }

    private IntentResult copyContext(IntentCommand command, IntentOptions options) {
        require(command.title(), "title");
        if ("TASK".equalsIgnoreCase(options.referenceKind())) {
            Long id = contextService.taskIdAt(null);
            if (id == null) {
                return IntentResult.clarificationNeeded("目前沒有可複製的待辦。");
            }
            Task source = taskService.getTask(id);
            Task copy = taskService.createTask(command.title(), source.getDescription(), source.getPriority(),
                    parse(command.dueAt()), source.getCategory(), source.getRecurrence(),
                    Task.ConditionType.NONE, null);
            contextService.rememberTask(copy);
            return IntentResult.taskCreated(copy);
        }
        Long id = contextService.scheduleIdAt(null);
        if (id == null) {
            return IntentResult.clarificationNeeded("目前沒有可複製的行程。");
        }
        ScheduleItem source = scheduleService.getSchedule(id);
        Instant start = parse(command.startAt());
        if (start == null) {
            start = source.getStartAt().plus(Duration.ofDays(1));
        }
        Instant end = start.plus(Duration.between(source.getStartAt(), source.getEndAt()));
        var decision = scheduleService.createSchedule(
                command.title(), start, end, source.getPlaceId(), source.getRecurrence(),
                source.getRecurrence() == ScheduleItem.Recurrence.NONE
                        ? null
                        : source.getRecurrenceUntil());
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleDecided(decision);
    }

    private Task taskTarget(IntentCommand command, IntentOptions options) {
        if (command.title() != null && !command.title().isBlank()) {
            List<Task> matches = taskService.findOpenTasksMatching(command.title());
            if (matches.size() != 1) {
                throw new IllegalArgumentException("task target is not unique");
            }
            return matches.getFirst();
        }
        Long id = contextService.taskIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("task context missing");
        }
        return taskService.getTask(id);
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

    private static TriggerType parseTrigger(String value) {
        try {
            return TriggerType.valueOf(value == null ? "ENTER" : value.toUpperCase());
        } catch (Exception exception) {
            return TriggerType.ENTER;
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
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
}
