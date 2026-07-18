package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Candidate;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Conversion;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LeapMonth;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LunarDate;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Status;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 農曆日期的安全對話邊界：先解析與可驗證換算，再要求確認，絕不把模型猜測寫入日曆。
 */
@Service
public class LunarCalendarConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int MAX_RECURRENCE_YEARS = 20;
    private static final Pattern LUNAR_DATE = Pattern.compile(
            "(?:農曆|陰曆)\\s*"
                    + "(?:(?<postYear>(?:西元)?\\d{4}年|民國\\d{2,3}年|今年|明年|去年)\\s*)?"
                    + "(?<leap>閏)?(?<month>[正冬臘腊一二三四五六七八九十兩0-9]{1,3})月\\s*"
                    + "(?<day>初?[一二三四五六七八九十兩]{1,3}|廿[一二三四五六七八九]?|卅|[0-9]{1,2})(?:日|號)?");
    private static final Pattern YEAR_RANGE = Pattern.compile(
            "(?:從)?\\s*(?<start>\\d{4})\\s*年?\\s*(?:到|至|[-－—～~])\\s*"
                    + "(?<end>\\d{4})\\s*年?");
    private static final Pattern ROC_YEAR = Pattern.compile("民國\\s*(\\d{2,3})\\s*年");
    private static final Pattern GREGORIAN_YEAR = Pattern.compile("(?:西元)?\\s*(20\\d{2})\\s*年");

    private final List<LunarCalendarConversionProvider> providers;
    private final Clock clock;

    public LunarCalendarConversationService(
            List<LunarCalendarConversionProvider> providers, Clock clock) {
        this.providers = List.copyOf(providers);
        this.clock = clock;
    }

    /** 回傳值存在時，主流程必須停止交給一般模型建立／修改資料。 */
    public Optional<IntentResult> answer(String text) {
        String normalized = normalize(text);
        if (!containsLunarMarker(normalized) || looksLikeMetaDiscussion(normalized)) {
            return Optional.empty();
        }

        Matcher dateMatcher = LUNAR_DATE.matcher(normalized);
        if (!dateMatcher.find()) {
            return clarification("我知道你提到農曆日期，但月或日還不完整。請用例如「2026 年農曆八月十五」或「2026 年農曆閏六月初三」；確認前不會建立或修改資料。");
        }

        Integer month = chineseNumber(dateMatcher.group("month"));
        Integer day = chineseNumber(stripInitial(dateMatcher.group("day")));
        if (month == null || month < 1 || month > 12 || day == null || day < 1 || day > 30) {
            return clarification("農曆月日「%s月%s」無法辨識。農曆只有一至十二月，每月日期最多到三十；請重新輸入，確認前不會建立或修改資料。"
                    .formatted(dateMatcher.group("month"), dateMatcher.group("day")));
        }

        LeapMonth leapMonth = explicitLeapPreference(normalized, dateMatcher);
        if (isAnnual(normalized)) {
            return answerAnnual(normalized, month, day, leapMonth);
        }

        Integer year = resolveYear(normalized, dateMatcher.group("postYear"));
        if (year == null) {
            return clarification("農曆 %s還缺年份；同一個農曆月日每年的國曆日期不同。請說「今年」、民國年或四位西元年，確認前不會建立或修改資料。"
                    .formatted(lunarLabel(month, day, leapMonth)));
        }
        return convertOne(new LunarDate(year, month, day, leapMonth), hasMutationIntent(normalized));
    }

    private Optional<IntentResult> answerAnnual(
            String text, int month, int day, LeapMonth leapMonth) {
        Matcher range = YEAR_RANGE.matcher(text);
        if (!range.find()) {
            return clarification("我已辨識為每年農曆%s的重複規則。這不能存成固定國曆每年重複，必須逐年換算；請補開始與截止年份，例如「2026 到 2030 年每年農曆八月十五」，確認前不會建立行程。"
                    .formatted(lunarLabel(month, day, leapMonth)));
        }
        int start = Integer.parseInt(range.group("start"));
        int end = Integer.parseInt(range.group("end"));
        if (end < start) {
            return clarification("農曆重複規則的截止年份早於開始年份，請重新確認年份範圍；目前不會建立行程。");
        }
        if (end - start + 1 > MAX_RECURRENCE_YEARS) {
            return clarification("一次最多預覽 %d 個農曆年份，避免在未核對前大量建立。請縮短年份範圍；目前不會建立行程。"
                    .formatted(MAX_RECURRENCE_YEARS));
        }
        Optional<LunarCalendarConversionProvider> provider = provider();
        if (provider.isEmpty()) {
            return unavailable(lunarLabel(month, day, leapMonth));
        }

        List<String> dates = new ArrayList<>();
        String source = null;
        for (int year = start; year <= end; year++) {
            Conversion conversion = safeConvert(provider.get(), new LunarDate(year, month, day, leapMonth));
            if (conversion.status() != Status.RESOLVED) {
                return conversionProblem(new LunarDate(year, month, day, leapMonth), conversion);
            }
            Candidate candidate = conversion.candidates().getFirst();
            dates.add("%d 農曆年：%s".formatted(year, CalendarDatePolicy.format(candidate.gregorianDate())));
            source = source == null ? conversion.sourceReference() : source;
        }
        String message = "農曆年度規則已逐年換算，尚未建立行程：\n"
                + String.join("\n", dates)
                + "\n來源：" + source
                + "\n請核對所有日期並明確回覆確認；正式儲存時也必須保留農曆規則，不能改成固定國曆每年重複。";
        return Optional.of(IntentResult.message(IntentResult.Action.CLARIFICATION_NEEDED, message));
    }

    private Optional<IntentResult> convertOne(LunarDate lunarDate, boolean mutationIntent) {
        Optional<LunarCalendarConversionProvider> provider = provider();
        if (provider.isEmpty()) {
            return unavailable(lunarLabel(lunarDate.month(), lunarDate.day(), lunarDate.leapMonth()));
        }
        Conversion conversion = safeConvert(provider.get(), lunarDate);
        if (conversion.status() != Status.RESOLVED) {
            return conversionProblem(lunarDate, conversion);
        }
        Candidate candidate = conversion.candidates().getFirst();
        String action = mutationIntent
                ? "請明確回覆「確認國曆 %s」後，才能繼續建立或修改資料。"
                        .formatted(CalendarDatePolicy.format(candidate.gregorianDate()))
                : "這次只回覆換算結果，不會建立或修改資料。";
        String message = "%d 農曆年%s換算為國曆 %s。\n來源：%s\n%s"
                .formatted(lunarDate.year(),
                        lunarLabel(lunarDate.month(), lunarDate.day(), candidate.leapMonth()),
                        CalendarDatePolicy.format(candidate.gregorianDate()),
                        conversion.sourceReference(), action);
        return Optional.of(IntentResult.message(
                mutationIntent ? IntentResult.Action.CLARIFICATION_NEEDED : IntentResult.Action.SOCIAL_REPLIED,
                message));
    }

    private Optional<IntentResult> conversionProblem(LunarDate lunarDate, Conversion conversion) {
        if (conversion.status() == Status.AMBIGUOUS_LEAP_MONTH) {
            String candidates = conversion.candidates().stream()
                    .map(candidate -> "%s：%s".formatted(
                            candidate.leapMonth() == LeapMonth.LEAP ? "閏月" : "一般月份",
                            CalendarDatePolicy.format(candidate.gregorianDate())))
                    .reduce((left, right) -> left + "；" + right).orElse("無候選日期");
            return clarification("%d 農曆年%s同時有一般月份與閏月的可能：%s。請明確說「一般%s月」或「閏%s月」；來源：%s。確認前不會建立或修改資料。"
                    .formatted(lunarDate.year(), lunarLabel(lunarDate.month(), lunarDate.day(), LeapMonth.UNSPECIFIED),
                            candidates, monthText(lunarDate.month()), monthText(lunarDate.month()),
                            conversion.sourceReference()));
        }
        String reason = switch (conversion.status()) {
            case INVALID_DATE -> "該農曆年沒有這個月日或閏月";
            case OUT_OF_RANGE -> "超出目前可驗證曆法資料的年份範圍";
            case TEMPORARILY_UNAVAILABLE -> "曆法來源目前暫時無法查核";
            case RESOLVED, AMBIGUOUS_LEAP_MONTH -> throw new IllegalStateException("unexpected status");
        };
        String source = conversion.sourceReference().isBlank()
                ? "提供者未回傳可核對來源" : conversion.sourceReference();
        return clarification("無法安全換算 %d 農曆年%s：%s；來源狀態：%s。請提供官方國曆日期或稍後重試，確認前不會建立或修改資料。"
                .formatted(lunarDate.year(),
                        lunarLabel(lunarDate.month(), lunarDate.day(), lunarDate.leapMonth()),
                        reason, source));
    }

    private Conversion safeConvert(LunarCalendarConversionProvider provider, LunarDate lunarDate) {
        try {
            Conversion conversion = provider.convert(lunarDate);
            return conversion == null
                    ? Conversion.failed(Status.TEMPORARILY_UNAVAILABLE, "提供者未回傳結果")
                    : conversion;
        } catch (RuntimeException exception) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE, "提供者執行失敗");
        }
    }

    private Optional<LunarCalendarConversionProvider> provider() {
        return providers.size() == 1 ? Optional.of(providers.getFirst()) : Optional.empty();
    }

    private Optional<IntentResult> unavailable(String lunarDate) {
        String reason = providers.isEmpty()
                ? "目前沒有安裝可追溯來源的農曆曆法提供者"
                : "目前同時存在多個農曆提供者，尚未指定唯一可信來源";
        return clarification("已辨識農曆%s，但%s，因此不能安全換算。請提供經官方日曆核對的國曆日期；目前不會建立或修改資料，也不會用固定天數或模型猜測。"
                .formatted(lunarDate, reason));
    }

    private static Optional<IntentResult> clarification(String message) {
        return Optional.of(IntentResult.clarificationNeeded(message));
    }

    private Integer resolveYear(String text, String postYear) {
        String value = postYear == null ? "" : postYear;
        if (value.contains("今年") || text.contains("今年農曆") || text.contains("今年的農曆")) {
            return LocalDate.now(clock.withZone(TAIPEI)).getYear();
        }
        if (value.contains("明年") || text.contains("明年農曆") || text.contains("明年的農曆")) {
            return LocalDate.now(clock.withZone(TAIPEI)).getYear() + 1;
        }
        if (value.contains("去年") || text.contains("去年農曆") || text.contains("去年的農曆")) {
            return LocalDate.now(clock.withZone(TAIPEI)).getYear() - 1;
        }
        Matcher roc = ROC_YEAR.matcher(value.isBlank() ? text : value);
        if (roc.find()) return Integer.parseInt(roc.group(1)) + 1911;
        Matcher gregorian = GREGORIAN_YEAR.matcher(value.isBlank() ? text : value);
        return gregorian.find() ? Integer.parseInt(gregorian.group(1)) : null;
    }

    private static LeapMonth explicitLeapPreference(String text, Matcher dateMatcher) {
        if (dateMatcher.group("leap") != null) return LeapMonth.LEAP;
        if (text.contains("非閏月") || text.contains("不是閏月") || text.contains("一般月份")
                || text.contains("一般農曆") || text.contains("平月")) {
            return LeapMonth.REGULAR;
        }
        return LeapMonth.UNSPECIFIED;
    }

    private static String lunarLabel(int month, int day, LeapMonth leapMonth) {
        String leap = leapMonth == LeapMonth.LEAP ? "閏" : leapMonth == LeapMonth.REGULAR ? "一般" : "";
        return leap + monthText(month) + "月" + dayText(day);
    }

    private static String monthText(int month) {
        return switch (month) {
            case 1 -> "正";
            case 11 -> "冬";
            case 12 -> "臘";
            default -> chineseNumberText(month);
        };
    }

    private static String dayText(int day) {
        if (day < 11) return "初" + chineseNumberText(day);
        if (day < 20) return "十" + chineseDigit(day - 10);
        if (day == 20) return "二十";
        if (day < 30) return "廿" + chineseDigit(day - 20);
        return "三十";
    }

    private static String chineseNumberText(int number) {
        if (number < 10) return chineseDigit(number);
        if (number == 10) return "十";
        return "十" + chineseDigit(number - 10);
    }

    private static String chineseDigit(int number) {
        return "零一二三四五六七八九".substring(number, number + 1);
    }

    private static Integer chineseNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace("兩", "二");
        if (value.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(value);
        }
        if (value.equals("正")) return 1;
        if (value.equals("冬")) return 11;
        if (value.equals("臘") || value.equals("腊")) return 12;
        if (value.equals("廿")) return 20;
        if (value.startsWith("廿")) return 20 + digit(value.substring(1));
        if (value.equals("卅")) return 30;
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.substring(0, ten));
            int units = ten == value.length() - 1 ? 0 : digit(value.substring(ten + 1));
            return tens < 0 || units < 0 ? null : tens * 10 + units;
        }
        int single = digit(value);
        return single < 0 ? null : single;
    }

    private static int digit(String value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static String stripInitial(String value) {
        return value != null && value.startsWith("初") ? value.substring(1) : value;
    }

    private static boolean containsLunarMarker(String text) {
        return text.contains("農曆") || text.contains("陰曆");
    }

    private static boolean isAnnual(String text) {
        return text.contains("每年") || text.contains("年年") || text.contains("每一年的農曆");
    }

    private static boolean hasMutationIntent(String text) {
        return containsAny(text, "建立", "新增", "加入", "安排", "排", "行程", "提醒", "改期", "修改", "取消");
    }

    private static boolean looksLikeMetaDiscussion(String text) {
        return containsAny(text, "情境清單", "測試資料", "功能開發", "需求文件", "使用者說", "對話紀錄問題");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "")
                .replace('臺', '台').toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
