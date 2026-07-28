package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Canonical, bounded codec for server-side materialization command snapshots. */
final class KnowledgeMaterializationSnapshotCodec {

    private static final int MAX_COMPONENT_LENGTH = 4096;

    private KnowledgeMaterializationSnapshotCodec() {
    }

    static String encode(KnowledgeMaterializationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("materialization command is required");
        }
        return switch (command) {
            case KnowledgeMaterializationCommand.CreateTask create ->
                "TASK|"
                        + component(create.title())
                        + component(create.deadline())
                        + component(create.priority());
            case KnowledgeMaterializationCommand.CreateCalendarNode create ->
                "CALENDAR_NODE|"
                        + component(create.nodeKey())
                        + component(create.label())
                        + component(create.time());
            case KnowledgeMaterializationCommand.SetPlanningPreference set ->
                "PLANNING_PREFERENCE|"
                        + component(set.transferMinutes())
                        + component(set.mealMinutes());
            case KnowledgeMaterializationCommand.SetBufferRule set ->
                "BUFFER_RULE|"
                        + component(set.placeId())
                        + component(set.bufferMinutes())
                        + component(set.expectedExplicitRevision());
            case KnowledgeMaterializationCommand.CreateTaskReminder create ->
                "TASK_REMINDER|"
                        + component(create.taskId())
                        + component(create.remindAt());
            case KnowledgeMaterializationCommand.CreateCalendarReminder create ->
                "CALENDAR_REMINDER|"
                        + component(create.nodeId())
                        + component(create.expectedNodeRevision())
                        + component(reminderTiming(create.timing()))
                        + component(create.deliveryMode())
                        + component(create.ackInterval())
                        + component(create.maxAlerts())
                        + component(create.preferredChannel());
        };
    }

    static KnowledgeMaterializationCommand decode(String snapshot) {
        if (snapshot == null || snapshot.isBlank() || snapshot.length() > 32_768) {
            throw malformed();
        }
        int separator = snapshot.indexOf('|');
        if (separator <= 0) {
            throw malformed();
        }
        String kind = snapshot.substring(0, separator);
        Cursor cursor = new Cursor(snapshot, separator + 1);
        KnowledgeMaterializationCommand command = switch (kind) {
            case "TASK" -> new KnowledgeMaterializationCommand.CreateTask(
                    cursor.next(),
                    nullableInstant(cursor.next()),
                    enumValue(TaskPriority.class, cursor.next()));
            case "CALENDAR_NODE" ->
                new KnowledgeMaterializationCommand.CreateCalendarNode(
                        cursor.next(),
                        cursor.next(),
                        instant(cursor.next()));
            case "PLANNING_PREFERENCE" ->
                new KnowledgeMaterializationCommand.SetPlanningPreference(
                        integer(cursor.next()), integer(cursor.next()));
            case "BUFFER_RULE" -> new KnowledgeMaterializationCommand.SetBufferRule(
                    longValue(cursor.next()),
                    integer(cursor.next()),
                    longValue(cursor.next()));
            case "TASK_REMINDER" ->
                new KnowledgeMaterializationCommand.CreateTaskReminder(
                        longValue(cursor.next()), instant(cursor.next()));
            case "CALENDAR_REMINDER" ->
                new KnowledgeMaterializationCommand.CreateCalendarReminder(
                        uuid(cursor.next()),
                        longValue(cursor.next()),
                        reminderTiming(cursor.next()),
                        enumValue(CalendarReminderDeliveryMode.class, cursor.next()),
                        nullableDuration(cursor.next()),
                        nullableInteger(cursor.next()),
                        enumValue(NotificationChannel.class, cursor.next()));
            default -> throw malformed();
        };
        cursor.requireEnd();
        if (!encode(command).equals(snapshot)) {
            throw malformed();
        }
        return command;
    }

    private static String reminderTiming(
            KnowledgeMaterializationCommand.CalendarReminderTiming timing) {
        return switch (timing) {
            case KnowledgeMaterializationCommand.CalendarReminderTiming.Relative relative ->
                "RELATIVE|" + relative.offset().toSeconds();
            case KnowledgeMaterializationCommand.CalendarReminderTiming.Absolute absolute ->
                "ABSOLUTE|" + absolute.fireAt();
        };
    }

    private static KnowledgeMaterializationCommand.CalendarReminderTiming reminderTiming(
            String value) {
        if (value.startsWith("RELATIVE|")) {
            return new KnowledgeMaterializationCommand.CalendarReminderTiming.Relative(
                    Duration.ofSeconds(longValue(value.substring("RELATIVE|".length()))));
        }
        if (value.startsWith("ABSOLUTE|")) {
            return new KnowledgeMaterializationCommand.CalendarReminderTiming.Absolute(
                    instant(value.substring("ABSOLUTE|".length())));
        }
        throw malformed();
    }

    private static String component(Object value) {
        String text = value == null ? "<null>" : value.toString();
        return text.length() + ":" + text;
    }

    private static Instant nullableInstant(String value) {
        return isNull(value) ? null : instant(value);
    }

    private static Duration nullableDuration(String value) {
        if (isNull(value)) {
            return null;
        }
        try {
            Duration duration = Duration.parse(value);
            if (!duration.toString().equals(value)) {
                throw malformed();
            }
            return duration;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static Integer nullableInteger(String value) {
        return isNull(value) ? null : integer(value);
    }

    private static boolean isNull(String value) {
        return "<null>".equals(value);
    }

    private static Instant instant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw malformed();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw malformed();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static int integer(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (!Integer.toString(parsed).equals(value)) {
                throw malformed();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static long longValue(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) {
                throw malformed();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
                "Stored materialization command snapshot is malformed");
    }

    private static final class Cursor {
        private final String input;
        private int position;

        private Cursor(String input, int position) {
            this.input = input;
            this.position = position;
        }

        private String next() {
            int colon = input.indexOf(':', position);
            if (colon <= position) {
                throw malformed();
            }
            String lengthText = input.substring(position, colon);
            if (!lengthText.chars().allMatch(Character::isDigit)
                    || (lengthText.length() > 1 && lengthText.startsWith("0"))) {
                throw malformed();
            }
            int length = integer(lengthText);
            if (length < 0 || length > MAX_COMPONENT_LENGTH) {
                throw malformed();
            }
            int start = colon + 1;
            int end;
            try {
                end = Math.addExact(start, length);
            } catch (ArithmeticException overflow) {
                throw malformed();
            }
            if (end > input.length()) {
                throw malformed();
            }
            position = end;
            return input.substring(start, end);
        }

        private void requireEnd() {
            if (position != input.length()) {
                throw malformed();
            }
        }
    }
}
