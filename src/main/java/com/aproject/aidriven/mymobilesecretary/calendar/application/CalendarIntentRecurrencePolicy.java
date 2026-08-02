package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** Resolves only recurrence rules explicitly grounded in the user's source text. */
final class CalendarIntentRecurrencePolicy {

    private static final Set<String> RULES =
            Set.of("DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY_NTH_WEEKDAY");

    private CalendarIntentRecurrencePolicy() {}

    static Decision resolve(IntentCommand command) {
        boolean requested = Boolean.TRUE.equals(command.recurring())
                || hasText(command.safeOptions().recurrence());
        if (!requested) return Decision.none();
        String grounded = recurrenceFromSource(command.sourceText());
        String supplied = command.safeOptions().recurrence();
        String rule = hasText(supplied)
                ? supplied.strip().toUpperCase(Locale.ROOT)
                : grounded;
        if (rule == null
                || !RULES.contains(rule)
                || grounded == null
                || !grounded.equals(rule)) {
            return Decision.invalid();
        }
        LocalDate until = null;
        if (hasText(command.safeOptions().recurrenceUntil())) {
            try {
                until = LocalDate.parse(command.safeOptions().recurrenceUntil().strip());
            } catch (DateTimeParseException invalidDate) {
                return Decision.invalid();
            }
        }
        return Decision.valid(rule, until);
    }

    private static String recurrenceFromSource(String source) {
        String compact = source == null ? "" : source.replaceAll("\\s+", "");
        if (compact.contains("每個平日") || compact.contains("每平日")
                || compact.contains("每個上班日") || compact.contains("每上班日")) {
            return "WEEKDAYS";
        }
        if (compact.contains("每天") || compact.contains("每日")) return "DAILY";
        if (compact.contains("每週") || compact.contains("每周")
                || compact.contains("每星期") || compact.contains("每個禮拜")) {
            return "WEEKLY";
        }
        if ((compact.contains("每月") || compact.contains("每個月"))
                && (compact.contains("第一個") || compact.contains("第二個")
                        || compact.contains("第三個") || compact.contains("第四個")
                        || compact.contains("第五個") || compact.contains("最後一個"))) {
            return "MONTHLY_NTH_WEEKDAY";
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Decision(boolean requested, boolean valid, String rule, LocalDate until) {

        static Decision none() {
            return new Decision(false, true, null, null);
        }

        static Decision invalid() {
            return new Decision(true, false, null, null);
        }

        static Decision valid(String rule, LocalDate until) {
            return new Decision(true, true, rule, until);
        }
    }
}
