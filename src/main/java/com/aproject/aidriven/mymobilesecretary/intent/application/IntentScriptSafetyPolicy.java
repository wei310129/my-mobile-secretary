package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
    private static final Pattern REMINDER_UPDATE = Pattern.compile(
            "(?:提醒|預先通知|提前通知)[^，。；;]{0,30}"
                    + "(?:改成|改為|調整成|調整為|修改成|修改為|變更成|變更為)"
                    + "|(?:修改|調整|變更)[^，。；;]{0,16}(?:既有|原本|原先|目前)?"
                    + "(?:提醒|預先通知|提前通知)");

    private IntentScriptSafetyPolicy() {
    }

    static IntentScript apply(String text, IntentScript script) {
        return apply(text, script, Clock.systemDefaultZone());
    }

    static IntentScript apply(String text, IntentScript script, Clock clock) {
        return apply(text, script, clock, false);
    }

    static IntentScript applyStrict(String text, IntentScript script, Clock clock) {
        return apply(text, script, clock, true);
    }

    private static IntentScript apply(String text, IntentScript script, Clock clock,
                                      boolean requireSourceText) {
        if (script == null || script.commands() == null) {
            return script;
        }
        IntentScript result = guardSourceGrounding(text, script, requireSourceText);
        result = normalizeMissingCommandTypes(result);
        result = CalendarDatePolicy.guard(text, result, clock);
        if (result.commands().stream().anyMatch(command ->
                command != null && command.type() == IntentCommand.Type.UNKNOWN
                        && CalendarDatePolicy.clarification(text, clock).isPresent())) {
            return result;
        }
        result = guardExplicitDraftOnly(text, result);
        result = guardUnsupportedReminderUpdate(text, result);
        result = guardUnsupportedConditionalRecurrence(text, result);
        result = normalizeCaregivingReminders(text, result);
        result = guardReportedNoticeWithoutEnd(text, result);
        return normalizeScheduleReminder(text, result);
    }

    private static IntentScript normalizeMissingCommandTypes(IntentScript script) {
        List<IntentCommand> normalized = new ArrayList<>();
        for (IntentCommand command : script.commands()) {
            if (command == null) continue;
            if (command.type() != null) {
                normalized.add(command);
                continue;
            }
            String reason = command.reason() == null || command.reason().isBlank()
                    ? "我還無法安全判斷要執行哪一種操作；確認前不會修改資料。"
                    : command.reason();
            normalized.add(new IntentCommand(IntentCommand.Type.UNKNOWN,
                    command.title(), null, null, null, command.placeName(), null, reason,
                    null, null, null, null, null, null, command.sourceText()));
        }
        return new IntentScript(List.copyOf(normalized));
    }

    private static IntentScript guardExplicitDraftOnly(String text, IntentScript script) {
        String compact = compact(text);
        boolean draftOnly = containsAny(compact, "保留草稿", "先留草稿", "先幫我留草稿", "只留草稿")
                || containsAny(compact, "不要直接建立", "不要建立正式", "先不要建立正式");
        if (!draftOnly) return script;
        return rejectMatching(script, IntentScriptSafetyPolicy::isDirectCreation,
                "你要求只保留草稿；目前沒有可安全執行的 typed 草稿操作，確認前不會建立正式資料。");
    }

    private static IntentScript guardUnsupportedReminderUpdate(String text, IntentScript script) {
        if (!REMINDER_UPDATE.matcher(compact(text)).find()) return script;
        return rejectMatching(script, IntentScriptSafetyPolicy::isReminderUpdateMutation,
                "你要修改既有提醒；目前還不能安全修改既有提醒，"
                        + "確認前不會新增提醒或更動原行程。");
    }

    private static IntentScript guardUnsupportedConditionalRecurrence(
            String text, IntentScript script) {
        String compact = compact(text);
        boolean recurring = containsAny(compact, "每週", "每周", "每星期", "每個禮拜", "固定");
        boolean conditional = containsAny(compact, "如果", "若", "遇到", "逢")
                && containsAny(compact, "假日", "國定假日", "颱風", "停班", "停課", "提前", "順延", "跳過");
        if (!recurring || !conditional) return script;
        return rejectMatching(script, IntentScriptSafetyPolicy::isScheduleCreationOrRecurrence,
                "這個固定行程包含條件式改期或跳過規則；無法完整保存規則時不會降格成普通每週行程。");
    }

    private static IntentScript rejectMatching(
            IntentScript script,
            java.util.function.Predicate<IntentCommand> rejected,
            String reason) {
        List<IntentCommand> safe = new ArrayList<>();
        boolean removed = false;
        for (IntentCommand command : script.commands()) {
            if (command != null && rejected.test(command)) {
                removed = true;
            } else if (command != null) {
                safe.add(command);
            }
        }
        if (removed) safe.add(unknown(reason));
        return new IntentScript(List.copyOf(safe));
    }

    private static boolean isDirectCreation(IntentCommand command) {
        if (command.type() == null) return false;
        return switch (command.type()) {
            case CREATE_SCHEDULE, CREATE_RELATIVE_SCHEDULE, CREATE_TASK,
                    CREATE_FLEXIBLE_DAY_TASK, BOOK_RESTAURANT -> true;
            default -> false;
        };
    }

    private static boolean isScheduleCreationOrRecurrence(IntentCommand command) {
        if (command.type() == null) return false;
        return switch (command.type()) {
            case CREATE_SCHEDULE, CREATE_RELATIVE_SCHEDULE, SET_SCHEDULE_RECURRING -> true;
            default -> false;
        };
    }

    private static boolean isReminderUpdateMutation(IntentCommand command) {
        if (command.type() == null) return false;
        return switch (command.type()) {
            case ADD_SCHEDULE_REMINDER, CREATE_SCHEDULE, CREATE_RELATIVE_SCHEDULE,
                    UPDATE_SCHEDULE, RESCHEDULE_SCHEDULE -> true;
            default -> false;
        };
    }

    private static IntentScript normalizeCaregivingReminders(
            String text, IntentScript script) {
        List<IntentCommand> normalized = new ArrayList<>();
        for (IntentCommand command : script.commands()) {
            if (!isCaregivingTransportSchedule(text, command)) {
                normalized.add(command);
                continue;
            }
            if (command.startAt() == null || command.startAt().isBlank()) {
                normalized.add(new IntentCommand(
                        IntentCommand.Type.UNKNOWN,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "請告訴我要在幾點提醒你接送；確認前不會建立時段。",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.sourceText()));
                continue;
            }
            normalized.add(new IntentCommand(
                    IntentCommand.Type.CREATE_TASK,
                    command.title(),
                    command.startAt(),
                    null,
                    null,
                    command.placeName(),
                    command.priority(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    command.recurring(),
                    command.safeOptions(),
                    command.sourceText()));
        }
        return new IntentScript(List.copyOf(normalized));
    }

    private static boolean isCaregivingTransportSchedule(
            String text, IntentCommand command) {
        if (command == null || command.type() != IntentCommand.Type.CREATE_SCHEDULE) {
            return false;
        }
        String source = command.sourceText() == null || command.sourceText().isBlank()
                ? text
                : command.sourceText();
        String evidence = compact((command.title() == null ? "" : command.title())
                + " " + (source == null ? "" : source));
        return TransportSemanticPolicy.isTransportToDependentActivity(evidence);
    }

    private static IntentScript guardSourceGrounding(String text, IntentScript script,
                                                     boolean requireSourceText) {
        String normalizedText = normalizeSource(text);
        List<IntentCommand> grounded = new ArrayList<>();
        boolean rejected = false;
        for (IntentCommand command : script.commands()) {
            if (command == null) {
                continue;
            }
            String normalizedSource = normalizeSource(command.sourceText());
            if (normalizedSource.isBlank()) {
                if (requireSourceText) {
                    rejected = true;
                } else {
                    grounded.add(command);
                }
                continue;
            }
            if (!normalizedText.contains(normalizedSource)) {
                rejected = true;
                continue;
            }
            if (isQuotedReferenceOnly(text, command.sourceText())) {
                rejected = true;
                continue;
            }
            grounded.add(command);
        }
        if (rejected) {
            grounded.add(new IntentCommand(IntentCommand.Type.UNKNOWN,
                    null, null, null, null, null, null,
                    "有一個操作無法對應到你這次的原話；確認前不會執行。",
                    null, null, null, null, null, null, text));
        }
        return new IntentScript(List.copyOf(grounded));
    }

    private static boolean isQuotedReferenceOnly(String text, String sourceText) {
        String current = text == null ? "" : text;
        String source = sourceText == null ? "" : sourceText.strip();
        if (source.isBlank() || !isExplicitlyQuoted(current, source)) {
            return false;
        }
        String compact = compact(current);
        return containsAny(compact,
                "只是引用", "僅是引用", "只是前一則", "只是上一則", "只是例子",
                "只是範例", "用來說明", "拿來說明", "不是新指令", "不是新的指令",
                "請勿執行", "不要執行", "不需執行", "不用執行", "別執行");
    }

    private static boolean isExplicitlyQuoted(String text, String source) {
        return text.contains("「" + source + "」")
                || text.contains("『" + source + "』")
                || text.contains("\"" + source + "\"")
                || text.contains("'" + source + "'");
    }

    private static IntentScript guardReportedNoticeWithoutEnd(String text, IntentScript script) {
        String compact = compact(text);
        if (!ReportedEventNoticePolicy.isReportedNotice(compact) || hasExplicitEnd(compact)) {
            return script;
        }

        List<IntentCommand> safe = new ArrayList<>();
        boolean removed = false;
        for (IntentCommand command : script.commands()) {
            if (command != null && command.type() == IntentCommand.Type.CREATE_SCHEDULE
                    && belongsToReportedNotice(command)) {
                removed = true;
                continue;
            }
            safe.add(command);
        }
        if (removed) {
            safe.add(unknown("轉述的活動通知缺活動結束時間；確認前不會建立行程，"
                    + "也不會自行補一小時或其他時長。"));
        }
        return new IntentScript(List.copyOf(safe));
    }

    /**
     * 舊版 command 沒有來源片段時維持 fail-closed；新版輸出可只阻擋老師通知那一項，
     * 讓同句其他已具備明確時間的行程不會被連帶丟棄。
     */
    private static boolean belongsToReportedNotice(IntentCommand command) {
        String source = compact(command.sourceText());
        if (source.isBlank()) {
            return true;
        }
        return ReportedEventNoticePolicy.isReportedNotice(source);
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
                    IntentOptions.empty().withLeadMinutes(leadMinutes), matcher.group(0));
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
                command.safeOptions().withLeadMinutes(leadMinutes), command.sourceText());
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

    private static String normalizeSource(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "")
                .replaceAll("[，,;；。！？!?：:「」『』\"'（）()\\[\\]]", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
