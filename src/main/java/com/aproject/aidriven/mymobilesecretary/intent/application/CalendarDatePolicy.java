package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 民國／西元日期的確定性辨識、澄清與穩定回顯。 */
public final class CalendarDatePolicy {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "(?:(?<era>民國|西元|國曆)\\s*)?"
                    + "(?<year>\\d{2,4})\\s*(?:年|[./-])\\s*"
                    + "(?<month>\\d{1,2})\\s*(?:月|[./-])\\s*"
                    + "(?<day>\\d{1,2})\\s*日?");

    private CalendarDatePolicy() {
    }

    static Optional<IntentResult> answer(String text, Clock clock) {
        String compact = compact(text);
        if (isTodayDateQuestion(compact)) {
            return Optional.of(IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "今天是" + describe(LocalDate.now(clock.withZone(TAIPEI))) + "。"));
        }
        if (!isWeekdayQuestion(compact)) {
            return Optional.empty();
        }
        Analysis analysis = analyze(text);
        if (analysis.problem() != null) {
            return Optional.of(IntentResult.clarificationNeeded(analysis.problem()));
        }
        if (analysis.firstDate() != null) {
            return Optional.of(IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    describe(analysis.firstDate()) + "。"));
        }
        return Optional.empty();
    }

    static Optional<String> clarification(String text) {
        return Optional.ofNullable(analyze(text).problem());
    }

    static String normalizeForInterpretation(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher matcher = DATE_TOKEN.matcher(text);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            ParsedDate parsed = parse(matcher);
            if (parsed.problem() != null || parsed.date() == null) continue;
            matcher.appendReplacement(normalized,
                    Matcher.quoteReplacement(format(parsed.date()) + "（原文：" + matcher.group() + "）"));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    static IntentScript guard(String text, IntentScript script) {
        Optional<String> problem = clarification(text);
        if (problem.isEmpty()) return script;
        IntentCommand unknown = new IntentCommand(IntentCommand.Type.UNKNOWN,
                null, null, null, null, null, null, problem.get(),
                null, null, null, null, null);
        return new IntentScript(java.util.List.of(unknown));
    }

    public static String format(LocalDate date) {
        char weekday = "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
        return "%04d/%02d/%02d（%s）".formatted(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), weekday);
    }

    static String describe(LocalDate date) {
        return "國曆 %s，民國 %03d/%02d/%02d（%s）".formatted(
                format(date), date.getYear() - 1911, date.getMonthValue(), date.getDayOfMonth(),
                "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1));
    }

    private static Analysis analyze(String text) {
        if (text == null || text.isBlank()) return new Analysis(null, null);
        Matcher matcher = DATE_TOKEN.matcher(text);
        LocalDate first = null;
        while (matcher.find()) {
            ParsedDate parsed = parse(matcher);
            if (parsed.problem() != null) return new Analysis(null, parsed.problem());
            if (first == null) first = parsed.date();
        }
        return new Analysis(first, null);
    }

    private static ParsedDate parse(Matcher matcher) {
        String era = matcher.group("era");
        int inputYear = Integer.parseInt(matcher.group("year"));
        int gregorianYear;
        if ("民國".equals(era)) {
            gregorianYear = inputYear + 1911;
        } else if ("西元".equals(era) || "國曆".equals(era)) {
            gregorianYear = inputYear;
        } else if (inputYear >= 1911) {
            gregorianYear = inputYear;
        } else if (inputYear >= 100 && inputYear <= 300) {
            gregorianYear = inputYear + 1911;
        } else {
            return new ParsedDate(null,
                    "年份「%s」無法確定是民國或西元。請改成例如「民國 115/07/18」或「西元 2026/07/18」；確認前不會建立或修改資料。"
                            .formatted(matcher.group("year")));
        }
        try {
            return new ParsedDate(LocalDate.of(gregorianYear,
                    Integer.parseInt(matcher.group("month")),
                    Integer.parseInt(matcher.group("day"))), null);
        } catch (DateTimeException exception) {
            return new ParsedDate(null,
                    "日期「%s」不存在或格式無法辨識。請用「民國 115/07/18」或「西元 2026/07/18」重新輸入；確認前不會建立或修改資料。"
                            .formatted(matcher.group().strip()));
        }
    }

    private static boolean isTodayDateQuestion(String compact) {
        return compact.matches("(?:請問)?今天(?:是)?(?:日期|幾月幾號|幾月幾日|幾號)(?:是什麼)?[？?嗎]*")
                || compact.matches("(?:請問)?(?:今天)?日期是什麼[？?嗎]*");
    }

    private static boolean isWeekdayQuestion(String compact) {
        return compact.contains("星期幾") || compact.contains("禮拜幾")
                || compact.contains("週幾") || compact.contains("星期幾號");
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private record Analysis(LocalDate firstDate, String problem) {
    }

    private record ParsedDate(LocalDate date, String problem) {
    }
}
