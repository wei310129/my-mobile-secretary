package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Rejects past mutation timestamps and proposes the next same local clock time for confirmation. */
final class PastMutationTimePolicy {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private PastMutationTimePolicy() {}

    static IntentScript guard(IntentScript script, Clock clock) {
        Instant now = clock.instant();
        for (IntentCommand command : script.commands()) {
            if (command == null || !isTimeMutation(command.type())) continue;
            Instant candidate = mutationTime(command);
            if (candidate == null || !candidate.isBefore(now)) continue;
            ZonedDateTime next = candidate.atZone(TAIPEI);
            ZonedDateTime threshold = now.atZone(TAIPEI);
            do {
                next = next.plusDays(1);
            } while (!next.toInstant().isAfter(now));
            return new IntentScript(List.of(new IntentCommand(
                    IntentCommand.Type.UNKNOWN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "你給的時間已經過了。下一個相同時間是 %s；要改到這個時間嗎？確認前不會建立或修改資料。"
                            .formatted(next.format(DISPLAY)),
                    null,
                    null,
                    null,
                    null,
                    null)));
        }
        return script;
    }

    private static Instant mutationTime(IntentCommand command) {
        String value = command.startAt() == null || command.startAt().isBlank()
                ? command.dueAt()
                : command.startAt();
        if (value == null || value.isBlank()) return null;
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static boolean isTimeMutation(IntentCommand.Type type) {
        if (type == null) return false;
        return switch (type) {
            case CREATE_SCHEDULE,
                    CREATE_RELATIVE_SCHEDULE,
                    CREATE_TASK,
                    CREATE_FLEXIBLE_DAY_TASK,
                    RESCHEDULE_SCHEDULE,
                    RESCHEDULE_TASK -> true;
            default -> false;
        };
    }
}
