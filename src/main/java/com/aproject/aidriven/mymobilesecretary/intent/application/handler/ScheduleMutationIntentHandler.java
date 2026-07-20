package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Executes schedule creation, rescheduling, recurrence changes and bounded cancellation. */
@Component
@RequiredArgsConstructor
public final class ScheduleMutationIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.CREATE_SCHEDULE,
            IntentCommand.Type.CREATE_RELATIVE_SCHEDULE,
            IntentCommand.Type.CANCEL_SCHEDULE,
            IntentCommand.Type.RESCHEDULE_SCHEDULE,
            IntentCommand.Type.SET_SCHEDULE_RECURRING,
            IntentCommand.Type.RESIZE_SCHEDULE,
            IntentCommand.Type.BULK_CANCEL_SCHEDULES);

    private final ScheduleService scheduleService;
    private final PlaceAliasService placeAliasService;
    private final ConversationContextService contextService;
    private final BulkScheduleCancellationService bulkCancellationService;
    private final TaskMutationIntentHandler taskMutationHandler;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case CREATE_SCHEDULE -> createSchedule(text, command);
            case CANCEL_SCHEDULE -> cancelSchedule(command);
            case RESCHEDULE_SCHEDULE -> rescheduleSchedule(command);
            case SET_SCHEDULE_RECURRING -> setScheduleRecurring(command);
            case BULK_CANCEL_SCHEDULES -> "PREVIEW_PRIVATE_ONLY".equalsIgnoreCase(
                    command.safeOptions().filter())
                            ? bulkCancellationService.previewPrivateWithin(
                                    parse(command.startAt()), parse(command.endAt()))
                            : bulkCancellationService.cancelWithin(
                                    parse(command.startAt()), parse(command.endAt()));
            case CREATE_RELATIVE_SCHEDULE, RESIZE_SCHEDULE -> handleLifestyleMutation(command);
            default -> throw new IllegalArgumentException(
                    "unsupported schedule mutation intent type " + command.type());
        };
    }

    private IntentResult createSchedule(String text, IntentCommand command) {
        require(command.title(), "title");
        Instant startAt = parse(command.startAt());
        Instant endAt = parse(command.endAt());
        if (startAt == null) {
            throw new IllegalArgumentException("schedule missing startAt");
        }
        if (endAt == null) {
            IntentCommand taskCommand = new IntentCommand(IntentCommand.Type.CREATE_TASK,
                    command.title(), command.startAt(), null, null,
                    command.placeName(), command.priority(), command.reason(),
                    null, null, null, null, false, command.options());
            return taskMutationHandler.handle(text, taskCommand);
        }
        Long placeId = placeAliasService.resolve(command.placeName()).map(Place::getId).orElse(null);
        ScheduleDecision decision = scheduleService.createSchedule(
                command.title(), startAt, endAt, placeId,
                parseScheduleRecurrence(command), parseRecurrenceUntil(command),
                parseScheduleCategory(command.safeOptions().category()));
        return IntentResult.scheduleDecided(decision);
    }

    private IntentResult cancelSchedule(IntentCommand command) {
        ScheduleMatch match = matchCancelableSchedule(command, "取消");
        return match.failure() != null ? match.failure()
                : IntentResult.scheduleCanceled(
                        scheduleService.cancelSchedule(match.item().getId()));
    }

    private IntentResult rescheduleSchedule(IntentCommand command) {
        Instant newStartAt = parse(command.startAt());
        if (newStartAt == null) {
            throw new IllegalArgumentException("schedule reschedule missing startAt");
        }
        ScheduleMatch match = matchReschedulableSchedule(command, "改");
        if (match.failure() != null) {
            return match.failure();
        }
        Instant newEndAt = parse(command.endAt());
        if (newEndAt == null) {
            newEndAt = newStartAt.plus(Duration.between(
                    match.item().getStartAt(), match.item().getEndAt()));
        }
        String recurrenceScope = command.safeOptions().recurrenceScope();
        if (match.item().getRecurrence() != ScheduleItem.Recurrence.NONE) {
            if (recurrenceScope == null || recurrenceScope.isBlank()) {
                return IntentResult.clarificationNeeded(
                        "「%s」是固定行程。這次改時間要只改本次,還是之後每一次都改?"
                                .formatted(match.item().getTitle()));
            }
            if ("THIS_OCCURRENCE".equalsIgnoreCase(recurrenceScope)) {
                return IntentResult.scheduleOccurrenceRescheduled(
                        scheduleService.rescheduleSingleOccurrence(
                                match.item().getId(), newStartAt, newEndAt));
            }
            if (!"SERIES".equalsIgnoreCase(recurrenceScope)) {
                return IntentResult.clarificationNeeded(
                        "請確認要只改這一次,還是修改整個固定系列。");
            }
        }
        return IntentResult.scheduleRescheduled(scheduleService.reschedule(
                match.item().getId(), newStartAt, newEndAt));
    }

    private IntentResult setScheduleRecurring(IntentCommand command) {
        require(command.title(), "title");
        boolean recurring = !Boolean.FALSE.equals(command.recurring());
        ScheduleMatch match = matchReschedulableSchedule(command.title(), "設定");
        return match.failure() != null ? match.failure()
                : IntentResult.scheduleRecurrenceSet(scheduleService.setRecurrence(
                        match.item().getId(), recurring
                                ? parseScheduleRecurrence(command) : ScheduleItem.Recurrence.NONE,
                        recurring ? parseRecurrenceUntil(command) : null));
    }

    private IntentResult handleLifestyleMutation(IntentCommand command) {
        try {
            IntentOptions options = command.safeOptions();
            return switch (command.type()) {
                case CREATE_RELATIVE_SCHEDULE -> createRelativeSchedule(command, options);
                case RESIZE_SCHEDULE -> resizeSchedule(command, options);
                default -> throw new IllegalArgumentException(
                        "unsupported schedule lifestyle mutation " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult createRelativeSchedule(IntentCommand command, IntentOptions options) {
        require(command.title(), "title");
        require(options.referenceTitle(), "referenceTitle");
        ScheduleItem reference = uniqueSchedule(options.referenceTitle());
        Duration duration = Duration.ofMinutes(positive(options.durationMinutes(), 60));
        boolean after = "AFTER".equalsIgnoreCase(options.referenceKind());
        Instant start = after ? reference.getEndAt() : reference.getStartAt().minus(duration);
        Instant end = after ? start.plus(duration) : reference.getStartAt();
        Long placeId = placeAliasService.resolve(command.placeName()).map(Place::getId).orElse(null);
        var decision = scheduleService.createSchedule(
                command.title(), start, end, placeId, false);
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleDecided(decision);
    }

    private IntentResult resizeSchedule(IntentCommand command, IntentOptions options) {
        ScheduleItem item = scheduleTarget(command, options);
        Instant start = parse(command.startAt());
        if (start == null) {
            start = item.getStartAt();
        }
        Instant end = parse(command.endAt());
        if (end == null && options.durationMinutes() != null && options.durationMinutes() > 0) {
            end = start.plus(Duration.ofMinutes(options.durationMinutes()));
        }
        if (end == null && options.shiftMinutes() != null && options.shiftMinutes() != 0) {
            end = item.getEndAt().plus(Duration.ofMinutes(options.shiftMinutes()));
        }
        if (end == null) {
            throw new IllegalArgumentException("missing schedule duration change");
        }
        var decision = scheduleService.reschedule(item.getId(), start, end);
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleMessage(IntentResult.Action.SCHEDULE_RESIZED,
                "已把「%s」調整為 %s–%s。".formatted(decision.item().getTitle(),
                        format(decision.item().getStartAt()),
                        ZonedDateTime.ofInstant(decision.item().getEndAt(), TAIPEI)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))), decision);
    }

    private ScheduleMatch matchCancelableSchedule(IntentCommand command, String actionVerb) {
        IntentOptions options = command.safeOptions();
        String primary = command.title();
        if ((primary == null || primary.isBlank())
                && options.referenceTitle() != null && !options.referenceTitle().isBlank()) {
            primary = options.referenceTitle();
        }
        if (primary != null && !primary.isBlank()) {
            List<ScheduleItem> candidates = scheduleService.findCancelableSchedulesMatching(primary);
            if (options.referenceTitle() != null && !options.referenceTitle().isBlank()) {
                String additional = normalize(options.referenceTitle());
                candidates = candidates.stream()
                        .filter(item -> normalize(item.getTitle()).contains(additional))
                        .toList();
            }
            String exclusion = exclusion(options.filter());
            if (exclusion != null) {
                candidates = candidates.stream()
                        .filter(item -> !normalize(item.getTitle()).contains(exclusion))
                        .toList();
            }
            Instant from = parse(command.startAt());
            Instant to = parse(command.endAt());
            if (from != null || to != null) {
                candidates = candidates.stream()
                        .filter(item -> from == null || !item.getStartAt().isBefore(from))
                        .filter(item -> to == null || item.getStartAt().isBefore(to))
                        .toList();
            }
            if (candidates.size() > 1 && options.ordinal() != null) {
                List<ScheduleItem> ordered = candidates.stream()
                        .sorted(Comparator.comparing(ScheduleItem::getStartAt))
                        .toList();
                int index = options.ordinal() - 1;
                candidates = index >= 0 && index < ordered.size()
                        ? List.of(ordered.get(index)) : List.of();
            }
            return uniqueScheduleMatch(candidates, primary, actionVerb);
        }
        return contextSchedule(options.ordinal());
    }

    private static String exclusion(String filter) {
        if (filter == null || !filter.toUpperCase().startsWith("EXCLUDE:")) {
            return null;
        }
        String value = normalize(filter.substring("EXCLUDE:".length()));
        return value.isBlank() ? null : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s　]+", "").toLowerCase();
    }

    private ScheduleMatch matchReschedulableSchedule(IntentCommand command, String actionVerb) {
        if (command.title() != null && !command.title().isBlank()) {
            return matchReschedulableSchedule(command.title(), actionVerb);
        }
        return contextSchedule(command.safeOptions().ordinal());
    }

    private ScheduleMatch matchReschedulableSchedule(String keyword, String actionVerb) {
        return uniqueScheduleMatch(
                scheduleService.findReschedulableSchedulesMatching(keyword), keyword, actionVerb);
    }

    private ScheduleMatch contextSchedule(Integer ordinal) {
        Long id = contextService.scheduleIdAt(ordinal);
        if (id == null) {
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "目前沒有可指代的行程,請說名稱或先列出行程。"));
        }
        return new ScheduleMatch(scheduleService.getSchedule(id), null);
    }

    private ScheduleMatch uniqueScheduleMatch(
            List<ScheduleItem> matches, String keyword, String actionVerb) {
        if (matches.isEmpty()) {
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "找不到跟「%s」有關、還能%s的行程。".formatted(keyword, actionVerb)));
        }
        if (matches.size() > 1) {
            String titles = matches.stream().limit(5)
                    .map(item -> "「%s」(%s)".formatted(item.getTitle(), format(item.getStartAt())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "有 %d 個行程都符合:\n%s\n\n請告訴我日期或時間，我才不會%s錯。"
                            .formatted(matches.size(), titles, actionVerb)));
        }
        return new ScheduleMatch(matches.getFirst(), null);
    }

    private ScheduleItem scheduleTarget(IntentCommand command, IntentOptions options) {
        String title = command.title();
        if ((title == null || title.isBlank()) && options.referenceTitle() != null) {
            title = options.referenceTitle();
        }
        if (title != null && !title.isBlank()) {
            return uniqueSchedule(title);
        }
        Long id = contextService.scheduleIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("schedule context missing");
        }
        return scheduleService.getSchedule(id);
    }

    private ScheduleItem uniqueSchedule(String title) {
        List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("schedule target is not unique");
        }
        return matches.getFirst();
    }

    private static ScheduleItem.Recurrence parseScheduleRecurrence(IntentCommand command) {
        String value = command.safeOptions().recurrence();
        if (value != null && !value.isBlank()) {
            try {
                return ScheduleItem.Recurrence.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Keep the prior boolean fallback when the model emits an unknown spelling.
            }
        }
        return Boolean.TRUE.equals(command.recurring())
                ? ScheduleItem.Recurrence.WEEKLY : ScheduleItem.Recurrence.NONE;
    }

    private static java.time.LocalDate parseRecurrenceUntil(IntentCommand command) {
        String value = command.safeOptions().recurrenceUntil();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("bad recurrenceUntil: " + value, exception);
        }
    }

    private static ScheduleItem.Category parseScheduleCategory(String value) {
        if (value == null || value.isBlank()) {
            return ScheduleItem.Category.UNKNOWN;
        }
        try {
            return ScheduleItem.Category.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return ScheduleItem.Category.UNKNOWN;
        }
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad time: " + value);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private record ScheduleMatch(ScheduleItem item, IntentResult failure) {
    }
}
