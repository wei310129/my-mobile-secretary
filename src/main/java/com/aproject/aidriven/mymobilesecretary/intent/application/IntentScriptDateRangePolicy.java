package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Adds explicit Taipei date bounds to read-only insight commands that name a date range. */
final class IntentScriptDateRangePolicy {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private IntentScriptDateRangePolicy() {
    }

    static IntentScript apply(String text, IntentScript script, Instant now) {
        if (script == null || script.commands() == null || now == null) {
            return script;
        }
        Optional<DateRange> range = resolve(text, now);
        if (range.isEmpty()) {
            return script;
        }
        List<IntentCommand> commands = new ArrayList<>();
        for (IntentCommand command : script.commands()) {
            if (command == null || !usesDateRange(command.type())
                    || hasText(command.startAt()) || hasText(command.endAt())) {
                commands.add(command);
                continue;
            }
            commands.add(copyWithRange(command, range.get()));
        }
        return new IntentScript(List.copyOf(commands));
    }

    private static Optional<DateRange> resolve(String text, Instant now) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        LocalDate start;
        LocalDate end;
        if (compact.contains("明天") || compact.contains("明日")) {
            start = today.plusDays(1);
            end = start.plusDays(1);
        } else if (compact.contains("下週") || compact.contains("下周")
                || compact.contains("下禮拜")) {
            start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            end = start.plusDays(7);
        } else if (compact.contains("這週") || compact.contains("本週")
                || compact.contains("這周") || compact.contains("本周")) {
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            end = start.plusDays(7);
        } else if (compact.contains("未來七天") || compact.contains("接下來七天")) {
            start = today;
            end = today.plusDays(7);
        } else if (compact.contains("今天") || compact.contains("今日")) {
            start = today;
            end = today.plusDays(1);
        } else {
            return Optional.empty();
        }
        return Optional.of(new DateRange(start.atStartOfDay(TAIPEI), end.atStartOfDay(TAIPEI)));
    }

    private static boolean usesDateRange(IntentCommand.Type type) {
        return type == IntentCommand.Type.SUGGEST_FREE_SLOT
                || type == IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY
                || type == IntentCommand.Type.ASK_LONGEST_SCHEDULE
                || type == IntentCommand.Type.GROUP_SCHEDULES_BY_PLACE
                || type == IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS;
    }

    private static IntentCommand copyWithRange(IntentCommand command, DateRange range) {
        return new IntentCommand(command.type(), command.title(), command.dueAt(),
                range.start().toOffsetDateTime().toString(),
                range.endExclusive().toOffsetDateTime().toString(), command.placeName(),
                command.priority(), command.reason(), command.onTime(), command.overrunMinutes(),
                command.outcomeReason(), command.windowHours(), command.recurring(), command.options());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DateRange(ZonedDateTime start, ZonedDateTime endExclusive) {
    }
}
