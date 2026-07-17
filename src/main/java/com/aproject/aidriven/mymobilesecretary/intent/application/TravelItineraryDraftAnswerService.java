package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.Entry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Conversation actions around an extracted itinerary draft. */
@Service
public class TravelItineraryDraftAnswerService {

    private final TravelItineraryDraftService draftService;

    public TravelItineraryDraftAnswerService(TravelItineraryDraftService draftService) {
        this.draftService = draftService;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String normalized = normalize(text);
        if (!mentionsItineraryDraft(normalized)) return Optional.empty();
        if (containsAny(normalized, "取消", "放棄", "不要匯入", "丟掉")) {
            beforeMutation.run();
            return Optional.of(discardLatest());
        }
        if (containsAny(normalized, "確認", "沒問題", "可以匯入", "同意匯入")) {
            beforeMutation.run();
            return Optional.of(confirmLatest());
        }
        if (containsAny(normalized, "顯示", "再看", "內容", "草稿", "剛才")) {
            return Optional.of(showLatest());
        }
        return Optional.empty();
    }

    public IntentResult showLatest() {
        return draftService.latestPending()
                .map(this::previewResult)
                .orElseGet(() -> IntentResult.clarificationNeeded(
                        "目前沒有等待確認的旅行行程表草稿，請先傳送清楚的行程表圖片。"));
    }

    public IntentResult confirmLatest() {
        return draftService.confirmLatest()
                .map(draft -> IntentResult.message(
                        IntentResult.Action.TRAVEL_ITINERARY_CONFIRMED,
                        "✅ 已確認旅行行程表「%s」。\n"
                                .formatted(draft.title())
                                + "- 行程段落｜" + draft.payload().entries().size() + " 段\n"
                                + "- 附加活動｜" + draft.payload().activities().size() + " 項\n"
                                + "- 注意事項｜" + draft.payload().notices().size() + " 項\n\n"
                                + "🧭 草稿已成為確認資料，後續可依段落建立行程、待辦與地點提醒。"))
                .orElseGet(() -> IntentResult.clarificationNeeded(
                        "目前沒有尚未過期、等待確認的旅行行程表草稿。"));
    }

    public IntentResult discardLatest() {
        return draftService.discardLatest()
                .map(draft -> IntentResult.message(
                        IntentResult.Action.TRAVEL_ITINERARY_DISCARDED,
                        "🗑️ 已放棄旅行行程表草稿「%s」。".formatted(draft.title())))
                .orElseGet(() -> IntentResult.clarificationNeeded(
                        "目前沒有等待處理的旅行行程表草稿。"));
    }

    public IntentResult previewResult(DraftView draft) {
        return IntentResult.message(IntentResult.Action.TRAVEL_ITINERARY_DRAFTED,
                previewMessage(draft));
    }

    public String previewMessage(DraftView draft) {
        List<Entry> entries = draft.payload().entries();
        String entryLines = entries.stream().limit(10).map(TravelItineraryDraftAnswerService::entryLine)
                .collect(Collectors.joining("\n"));
        if (entries.size() > 10) {
            entryLines += "\n……另有 " + (entries.size() - 10) + " 段";
        }
        StringBuilder message = new StringBuilder("🗺️ 已辨識旅行行程表「")
                .append(draft.title()).append("」，請先核對：\n")
                .append(entryLines.isBlank() ? "未讀到明確時段" : entryLines);
        appendBlock(message, "🎟️ 附加活動／報名資訊：", draft.payload().activities());
        appendBlock(message, "⚠️ 重要注意事項：", draft.payload().notices());
        message.append("\n\n❓ 資料正確請回覆「確認匯入行程表」。")
                .append("\n- 有明顯錯誤時，請回覆「放棄行程表草稿」後重傳清楚圖片")
                .append("\n- 確認前不會建立正式行程、待辦或地點提醒");
        return message.toString();
    }

    private static void appendBlock(StringBuilder message, String heading, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        message.append("\n\n").append(heading).append('\n')
                .append(lines.stream().limit(8).collect(Collectors.joining("\n")));
        if (lines.size() > 8) message.append("\n……另有 ").append(lines.size() - 8).append(" 項");
    }

    private static String entryLine(Entry entry) {
        String dateTime = joinNonBlank(" ", entry.date(), timeRange(entry));
        String subject = joinNonBlank("｜", entry.title(), entry.placeName());
        return joinNonBlank("｜", dateTime, subject, entry.details());
    }

    private static String timeRange(Entry entry) {
        if (isBlank(entry.startTime())) return entry.endTime();
        return isBlank(entry.endTime()) ? entry.startTime()
                : entry.startTime() + "–" + entry.endTime();
    }

    private static String joinNonBlank(String delimiter, String... values) {
        return java.util.Arrays.stream(values).filter(value -> !isBlank(value))
                .collect(Collectors.joining(delimiter));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean mentionsItineraryDraft(String text) {
        return containsAny(text, "行程表", "旅行草稿", "旅遊草稿", "行程草稿")
                && containsAny(text, "確認", "匯入", "草稿", "取消", "放棄", "顯示", "再看", "沒問題");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s　]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
