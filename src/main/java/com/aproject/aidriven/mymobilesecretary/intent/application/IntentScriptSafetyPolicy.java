package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic validation and repair for unsafe model-produced command scripts. */
final class IntentScriptSafetyPolicy {

    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:\\d{1,2}(?::\\d{2})?|[零一二三四五六七八九十兩]{1,3}點)"
                    + "(?:到|至|[-–~～])"
                    + "(?:\\d{1,2}(?::\\d{2})?|[零一二三四五六七八九十兩]{1,3}點)");
    private static final Pattern SCHEDULE_REMINDER = Pattern.compile(
            "(?<target>[^，。；;]{1,80}?)前(?<amount>\\d{1,3}|[一二三四五六七八九十兩]{1,3}|半)"
                    + "(?<unit>分鐘|分|小時)提醒(?:我|一下)?");

    private IntentScriptSafetyPolicy() {
    }

    static IntentScript apply(String text, IntentScript script) {
        return apply(text, script, Clock.systemDefaultZone());
    }

    static IntentScript apply(String text, IntentScript script, Clock clock) {
        if (script == null || script.commands() == null) {
            return script;
        }
        IntentScript result = CalendarDatePolicy.guard(text, script, clock);
        if (result.commands().stream().anyMatch(command ->
                command != null && command.type() == IntentCommand.Type.UNKNOWN
                        && CalendarDatePolicy.clarification(text, clock).isPresent())) {
            return result;
        }
        result = guardTeacherNoticeWithoutEnd(text, result);
        Optional<String> pickupQuestion = IntentService.schoolPickupClarification(text);
        if (pickupQuestion.isPresent()) {
            result = IntentService.applySchoolPickupSafeguard(result, pickupQuestion.get());
        }
        return normalizeScheduleReminder(text, result);
    }

    private static IntentScript guardTeacherNoticeWithoutEnd(String text, IntentScript script) {
        String compact = compact(text);
        boolean teacherNotice = compact.contains("老師")
                && containsAny(compact, "通知", "提醒", "老師說", "報到", "到校");
        if (!teacherNotice || hasExplicitEnd(compact)) {
            return script;
        }

        List<IntentCommand> safe = new ArrayList<>();
        boolean removed = false;
        for (IntentCommand command : script.commands()) {
            if (command != null && command.type() == IntentCommand.Type.CREATE_SCHEDULE) {
                removed = true;
                continue;
            }
            safe.add(command);
        }
        if (removed) {
            safe.add(unknown("老師通知尚缺活動結束時間；確認前不會建立行程，也不會自行補一小時。"));
        }
        return new IntentScript(List.copyOf(safe));
    }

    private static boolean hasExplicitEnd(String compact) {
        if (containsAny(compact, "沒有說幾點結束", "沒說幾點結束", "未說結束",
                "沒有結束時間", "不知道幾點結束", "沒說幾點下課", "不知道幾點下課")) {
            return false;
        }
        return TIME_RANGE.matcher(compact).find()
                || containsAny(compact, "結束", "下課", "放學", "離校");
    }

    private static IntentScript normalizeScheduleReminder(String text, IntentScript script) {
        Matcher matcher = SCHEDULE_REMINDER.matcher(compact(text));
        if (!matcher.find()) {
            return script;
        }
        int leadMinutes = leadMinutes(matcher.group("amount"), matcher.group("unit"));
        if (leadMinutes <= 0) {
            return script;
        }
        String parsedTarget = cleanReminderTarget(matcher.group("target"));
        boolean scheduleCue = containsAny(parsedTarget, "行程", "會議", "活動", "課程", "上課",
                "看診", "回診", "牙醫", "聚餐");
        boolean alreadyReminder = script.commands().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(command -> command.type() == IntentCommand.Type.ADD_SCHEDULE_REMINDER);
        if (!alreadyReminder && !scheduleCue) {
            return script;
        }

        List<IntentCommand> normalized = new ArrayList<>();
        boolean replaced = false;
        for (IntentCommand command : script.commands()) {
            if (command == null) continue;
            if (command.type() == IntentCommand.Type.ADD_SCHEDULE_REMINDER) {
                normalized.add(copyReminder(command, parsedTarget, leadMinutes));
                replaced = true;
            } else {
                normalized.add(command);
            }
        }
        if (!replaced) {
            IntentCommand schedule = script.commands().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(command -> command.type() == IntentCommand.Type.CREATE_SCHEDULE)
                    .findFirst().orElse(null);
            String target = schedule != null && schedule.title() != null
                    ? schedule.title() : parsedTarget;
            IntentCommand reminder = new IntentCommand(IntentCommand.Type.ADD_SCHEDULE_REMINDER,
                    target, null, null, null, null, null, null,
                    null, null, null, null, null,
                    IntentOptions.empty().withLeadMinutes(leadMinutes));
            if (normalized.size() == 1 && (normalized.getFirst().type() == IntentCommand.Type.CREATE_TASK
                    || normalized.getFirst().type() == IntentCommand.Type.UNKNOWN)) {
                normalized.set(0, reminder);
            } else {
                normalized.add(reminder);
            }
        }
        return new IntentScript(List.copyOf(normalized));
    }

    private static IntentCommand copyReminder(IntentCommand command, String parsedTarget,
                                              int leadMinutes) {
        return new IntentCommand(command.type(),
                command.title() == null || command.title().isBlank() ? parsedTarget : command.title(),
                command.dueAt(), command.startAt(), command.endAt(), command.placeName(),
                command.priority(), command.reason(), command.onTime(), command.overrunMinutes(),
                command.outcomeReason(), command.windowHours(), command.recurring(),
                command.safeOptions().withLeadMinutes(leadMinutes));
    }

    private static int leadMinutes(String amount, String unit) {
        int value;
        if ("半".equals(amount)) {
            value = "小時".equals(unit) ? 30 : 0;
        } else if (amount.chars().allMatch(Character::isDigit)) {
            value = Integer.parseInt(amount);
        } else {
            value = chineseNumber(amount);
        }
        return "小時".equals(unit) && !"半".equals(amount) ? value * 60 : value;
    }

    private static int chineseNumber(String value) {
        String normalized = value.replace('兩', '二');
        if (normalized.equals("十")) return 10;
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(normalized.charAt(ten - 1));
            int ones = ten == normalized.length() - 1 ? 0 : digit(normalized.charAt(ten + 1));
            return tens * 10 + ones;
        }
        return normalized.length() == 1 ? digit(normalized.charAt(0)) : 0;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static String cleanReminderTarget(String value) {
        return value.replaceFirst("^(?:請)?(?:幫我)?", "")
                .replaceFirst("^(?:今天|明天|後天|下週[一二三四五六日天]?)", "")
                .strip();
    }

    private static IntentCommand unknown(String reason) {
        return new IntentCommand(IntentCommand.Type.UNKNOWN, null, null, null, null,
                null, null, reason, null, null, null, null, null);
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
