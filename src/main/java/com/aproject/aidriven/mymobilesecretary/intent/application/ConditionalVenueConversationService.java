package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.decision.application.ConditionalVenueService;
import com.aproject.aidriven.mymobilesecretary.schedule.decision.domain.ConditionalVenueDraft;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 先保存互斥場地選項與決策提醒，選定後只建立一筆行程。 */
@Service
public class ConditionalVenueConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final Pattern TIME = Pattern.compile(
            ChineseTimePeriod.CAPTURING_REGEX + "?([零一二三四五六七八九十兩\\d]{1,3})"
                    + "(?:點|:)(半|[零一二三四五六七八九十兩\\d]{1,2}分?|[0-5]\\d)?");
    private static final Pattern DURATION = Pattern.compile(
            "(?:每次|維持|持續|運動|安排)?([零一二三四五六七八九十兩\\d]{1,3})(小時|鐘頭|分鐘)");
    private static final Pattern PRIMARY = Pattern.compile("(?:明天|明晚|今晚)?[^，,]{0,20}?去([^，,。]{1,80})");
    private static final Pattern FALLBACK = Pattern.compile(
            "(?:就|則)(?:改)?(?:在|去)([^，,。]{1,40}?)(?:運動|活動|$)");

    private final ConditionalVenueService venueService;
    private final Clock clock;

    public ConditionalVenueConversationService(ConditionalVenueService venueService, Clock clock) {
        this.venueService = venueService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(
            String text, ConversationSnapshot previous, Runnable beforeMutation) {
        String current = normalize(text);
        if (looksLikeMeta(current)) return Optional.empty();

        Optional<ConditionalVenueDraft> pending = venueService.latestPending();
        if (pending.isPresent()) {
            Optional<String> selection = selectedPlace(current, pending.get());
            if (selection.isPresent()) {
                beforeMutation.run();
                ConditionalVenueService.Resolution resolution =
                        venueService.resolve(pending.get(), selection.get());
                IntentResult decided = IntentResult.scheduleDecided(resolution.scheduleDecision());
                String binding = resolution.scheduleDecision().item().getPlaceId() == null
                        ? "；已保存場地文字，但尚未綁定可導航的精確地點"
                        : "；已綁定既有地點";
                return Optional.of(IntentResult.scheduleMessage(decided.action(),
                        "已選定「%s」%s。\n%s".formatted(selection.get(), binding, decided.message()),
                        resolution.scheduleDecision()));
            }
        }

        String compact = resumePending(current, previous);
        if (!isConditionalVenueRequest(compact)) return Optional.empty();
        Parsed parsed = parse(compact);
        List<String> missing = new ArrayList<>();
        if (parsed.eventAt() == null) missing.add("活動日期與開始時間");
        if (parsed.duration() == null) missing.add("活動持續多久");
        if (parsed.decisionAt() == null) missing.add("何時提醒決定場地");
        if (parsed.decisionAt() != null && !parsed.decisionPeriodExplicit()) {
            missing.add("決定場地的六點是上午或下午");
        }
        if (parsed.primaryPlace() == null || parsed.fallbackPlace() == null) {
            missing.add("原定與備用場地");
        }
        if (!missing.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我已辨識這是二選一的條件場地，只會建立一個最終行程，不會先建兩份。"
                            + "還需要確認：" + String.join("、", missing)
                            + "。補齊後我會先存條件場地草稿與決策提醒，選定前不建立行程。"));
        }
        if (!parsed.decisionAt().isBefore(parsed.eventAt())) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "決定場地的提醒必須早於活動開始時間；目前沒有建立草稿、提醒或行程。"));
        }

        beforeMutation.run();
        ConditionalVenueDraft draft = venueService.createDraft(
                "運動", parsed.eventAt(), parsed.duration(), parsed.primaryPlace(),
                parsed.fallbackPlace(), parsed.decisionAt());
        return Optional.of(IntentResult.message(IntentResult.Action.PLANNING_PREFERENCE_SET,
                "已保存條件場地草稿 #%d，尚未建立行程：\n"
                        .formatted(draft.getId())
                        + "- 活動｜%s，持續 %d 分鐘\n".formatted(
                                at(draft.getEventStartAt()), parsed.duration().toMinutes())
                        + "- 場地｜原定「%s」；條件不成立改「%s」\n".formatted(
                                draft.getPrimaryPlaceName(), draft.getFallbackPlaceName())
                        + "- 決策提醒｜%s\n\n".formatted(at(draft.getDecisionAt()))
                        + "到時告訴我最後選哪個場地；我只會建立一筆行程。"));
    }

    private Parsed parse(String text) {
        String expanded = text.replace("明晚", "明天晚上").replace("今晚", "今天晚上");
        LocalDate date = expanded.contains("明天")
                ? LocalDate.now(clock.withZone(TAIPEI)).plusDays(1) : null;
        List<ParsedTime> times = new ArrayList<>();
        Matcher matcher = TIME.matcher(expanded);
        while (matcher.find()) {
            Integer hour = number(matcher.group(2));
            int minute = minute(matcher.group(3));
            if (hour == null) continue;
            boolean explicit = matcher.group(1) != null || hour >= 13;
            int normalizedHour = ChineseTimePeriod.toTwentyFourHour(matcher.group(1), hour);
            if (normalizedHour >= 0 && normalizedHour <= 23 && minute >= 0 && minute <= 59) {
                times.add(new ParsedTime(LocalTime.of(normalizedHour, minute), explicit));
            }
        }
        ParsedTime eventTime = times.isEmpty() ? null : times.getFirst();
        ParsedTime decisionTime = times.size() < 2 ? null : times.getLast();
        ZonedDateTime eventAt = date == null || eventTime == null ? null
                : date.atTime(eventTime.value()).atZone(TAIPEI);
        ZonedDateTime decisionAt = date == null || decisionTime == null ? null
                : date.atTime(decisionTime.value()).atZone(TAIPEI);
        Matcher primary = PRIMARY.matcher(expanded);
        Matcher fallback = FALLBACK.matcher(expanded);
        return new Parsed(
                eventAt == null ? null : eventAt.toInstant(), duration(expanded),
                primary.find() ? cleanPlace(primary.group(1)) : null,
                fallback.find() ? cleanPlace(fallback.group(1)) : null,
                decisionAt == null ? null : decisionAt.toInstant(),
                decisionTime != null && decisionTime.explicit());
    }

    private static Optional<String> selectedPlace(String text, ConditionalVenueDraft draft) {
        boolean primary = text.contains(draft.getPrimaryPlaceName());
        boolean fallback = text.contains(draft.getFallbackPlaceName());
        if (containsAny(text, "休館", "沒開", "沒有開", "不營業") && fallback) {
            return Optional.of(draft.getFallbackPlaceName());
        }
        if (containsAny(text, "有開", "正常營業", "照原定") && primary) {
            return Optional.of(draft.getPrimaryPlaceName());
        }
        if (fallback && containsAny(text, "改在", "改去", "選", "就在")) {
            return Optional.of(draft.getFallbackPlaceName());
        }
        if (primary ^ fallback) return Optional.of(
                primary ? draft.getPrimaryPlaceName() : draft.getFallbackPlaceName());
        return Optional.empty();
    }

    private static Duration duration(String text) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.find()) return null;
        Integer amount = number(matcher.group(1));
        if (amount == null || amount <= 0) return null;
        return matcher.group(2).equals("分鐘")
                ? Duration.ofMinutes(amount) : Duration.ofHours(amount);
    }

    private static int minute(String value) {
        if (value == null || value.isBlank()) return 0;
        if (value.equals("半")) return 30;
        Integer parsed = number(value.replace("分", ""));
        return parsed == null ? -1 : parsed;
    }

    private static Integer number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace("兩", "二");
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.charAt(ten - 1));
            int units = ten == value.length() - 1 ? 0 : digit(value.charAt(ten + 1));
            return tens < 0 || units < 0 ? null : tens * 10 + units;
        }
        int single = value.length() == 1 ? digit(value.charAt(0)) : -1;
        return single < 0 ? null : single;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static boolean isConditionalVenueRequest(String text) {
        return (text.contains("若") || text.contains("如果"))
                && text.contains("只要一個行程") && text.contains("場地")
                && text.contains("決定") && PRIMARY.matcher(text).find()
                && FALLBACK.matcher(text).find();
    }

    private static String resumePending(String current, ConversationSnapshot previous) {
        if (current.isBlank() || isConditionalVenueRequest(current) || previous == null
                || !"CLARIFICATION_NEEDED".equals(previous.lastAction())
                || previous.lastUserText() == null || previous.lastAssistantText() == null
                || !previous.lastAssistantText().contains("條件場地")
                || (!DURATION.matcher(current).find()
                        && ChineseTimePeriod.containedPeriod(current) == null)) {
            return current;
        }
        return normalize(previous.lastUserText()) + "，" + current;
    }

    private static String cleanPlace(String value) {
        return value == null ? null : value.replaceFirst("^(?:臨時)?", "").strip();
    }

    private static String at(Instant value) {
        return value.atZone(TAIPEI).format(DISPLAY);
    }

    private static boolean looksLikeMeta(String text) {
        return containsAny(text, "情境清單", "測試資料", "功能開發", "需求文件", "使用者說");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private record Parsed(
            Instant eventAt, Duration duration, String primaryPlace, String fallbackPlace,
            Instant decisionAt, boolean decisionPeriodExplicit) {
    }

    private record ParsedTime(LocalTime value, boolean explicit) {
    }
}
