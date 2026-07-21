package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Resolves numbered schedule lists and explicit LINE quotes before generic draft/knowledge routes. */
@Service
public class ScheduleSelectionConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Pattern ORDINAL_DELETE = Pattern.compile(
            "(?:刪除|刪掉|移除|取消)(?:第)?(\\d{1,2})(?:[.、．])?(?:所指的?)?行程");
    private static final Pattern QUOTED_TITLE = Pattern.compile("[「『]([^」』]{2,160})[」』]");
    private static final Pattern QUOTED_SCHEDULE_REFERENCE = Pattern.compile(
            "(?:^|;)SCHEDULE:(\\d+):(\\d+)(?:;|$)");

    private final ScheduleService schedules;
    private final ConversationContextService context;

    public ScheduleSelectionConversationService(
            ScheduleService schedules, ConversationContextService context) {
        this.schedules = schedules;
        this.context = context;
    }

    public Optional<IntentResult> answer(
            String text, String interpretationText, Runnable beforeMutation) {
        String normalized = normalize(text);
        ConversationSnapshot snapshot = context.snapshot();
        if (isMergeRequest(normalized, snapshot)) {
            return Optional.of(mergeOrRecommend(snapshot, beforeMutation));
        }
        Integer keep = keepOrdinal(normalized, snapshot.lastAssistantText());
        if (keep != null) {
            return Optional.of(keepOneAndDiscardOther(snapshot, keep, beforeMutation));
        }
        Integer deleteOrdinal = deleteOrdinal(normalized);
        if (deleteOrdinal != null) {
            return Optional.of(discardByOrdinal(snapshot, deleteOrdinal, beforeMutation));
        }
        if (isQuotedScheduleDiscard(normalized, interpretationText)) {
            return Optional.of(discardQuotedSchedule(
                    snapshot, interpretationText, beforeMutation));
        }
        return Optional.empty();
    }

    private IntentResult mergeOrRecommend(ConversationSnapshot snapshot, Runnable beforeMutation) {
        List<Long> ids = snapshot.lastScheduleListIds();
        if (ids.size() < 2) {
            return IntentResult.clarificationNeeded(
                    "目前沒有可對應的前兩筆行程；請先重新列出當日行程。");
        }
        ScheduleItem first = schedules.getSchedule(ids.get(0));
        ScheduleItem second = schedules.getSchedule(ids.get(1));
        if (sameDetails(first, second)) {
            return keepOneAndDiscardOther(snapshot, 1, beforeMutation);
        }
        int firstScore = completeness(first);
        int secondScore = completeness(second);
        String recommendation = firstScore == secondScore ? ""
                : "\n\n建議保留第%s筆，因為它的資料較完整。你也可以回覆「照建議合併」。"
                        .formatted(firstScore > secondScore ? "一" : "二");
        return IntentResult.clarificationNeeded((
                "可以，前兩筆是：\n"
                        + "1. %s\n\n2. %s\n\n"
                        + "合併會保留其中一筆並終止另一筆。請回覆「保留第一個」或「保留第二個」。%s")
                .formatted(line(first), line(second), recommendation));
    }

    private IntentResult keepOneAndDiscardOther(
            ConversationSnapshot snapshot, int keepOrdinal, Runnable beforeMutation) {
        List<Long> ids = snapshot.lastScheduleListIds();
        if (ids.size() < 2 || keepOrdinal < 1 || keepOrdinal > 2) {
            return IntentResult.clarificationNeeded(
                    "我找不到剛才那兩筆行程；請先重新列出當日行程。");
        }
        ScheduleItem kept = schedules.getSchedule(ids.get(keepOrdinal - 1));
        ScheduleItem discarded = schedules.getSchedule(ids.get(keepOrdinal == 1 ? 1 : 0));
        if (isTerminal(discarded)) {
            return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "已保留「%s」；另一筆「%s」先前已終止，沒有重複異動。"
                            .formatted(kept.getTitle(), discarded.getTitle()));
        }
        beforeMutation.run();
        schedules.discardSchedule(discarded.getId());
        context.rememberSchedule(kept);
        return IntentResult.message(IntentResult.Action.SCHEDULE_CANCELED,
                "已合併為一筆：保留%s；另一筆%s。"
                        .formatted(line(kept), discardedLabel(discarded)));
    }

    private IntentResult discardByOrdinal(
            ConversationSnapshot snapshot, int ordinal, Runnable beforeMutation) {
        List<Long> ids = snapshot.lastScheduleListIds();
        if (ordinal < 1 || ordinal > ids.size()) {
            return IntentResult.clarificationNeeded(
                    "上一份行程清單沒有第 %d 筆；請先重新列出行程。".formatted(ordinal));
        }
        return discard(schedules.getSchedule(ids.get(ordinal - 1)), beforeMutation);
    }

    private IntentResult discardQuotedSchedule(
            ConversationSnapshot snapshot, String interpretationText, Runnable beforeMutation) {
        ScheduleItem target = quotedTarget(interpretationText).orElseGet(() -> {
            Long id = snapshot.lastScheduleId();
            return id == null ? null : schedules.getSchedule(id);
        });
        if (target == null) {
            return IntentResult.clarificationNeeded(
                    "這則引用沒有唯一指到一筆行程；請引用含名稱與時間的那則行程回覆。");
        }
        return discard(target, beforeMutation);
    }

    private IntentResult discard(ScheduleItem target, Runnable beforeMutation) {
        if (isTerminal(target)) {
            return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "行程「%s」已經是%s，沒有再次異動。"
                            .formatted(target.getTitle(), statusLabel(target.getStatus())));
        }
        ScheduleStatus prior = target.getStatus();
        beforeMutation.run();
        schedules.discardSchedule(target.getId());
        return IntentResult.message(IntentResult.Action.SCHEDULE_CANCELED,
                prior == ScheduleStatus.PROPOSED
                        ? "已放棄待確認行程「%s」，不會再出現在一般行程總覽。"
                                .formatted(target.getTitle())
                        : "已取消行程「%s」。".formatted(target.getTitle()));
    }

    private Optional<ScheduleItem> quotedTarget(String interpretationText) {
        if (interpretationText == null || !interpretationText.contains("【LINE 明確引用】")) {
            return Optional.empty();
        }
        Matcher typed = QUOTED_SCHEDULE_REFERENCE.matcher(interpretationText);
        if (typed.find()) {
            return Optional.of(schedules.getSchedule(Long.parseLong(typed.group(1))));
        }
        String quoted = interpretationText.substring(
                interpretationText.indexOf("【LINE 明確引用】"),
                interpretationText.indexOf("【使用者目前訊息】") >= 0
                        ? interpretationText.indexOf("【使用者目前訊息】")
                        : interpretationText.length());
        Matcher matcher = QUOTED_TITLE.matcher(quoted);
        while (matcher.find()) {
            String title = matcher.group(1);
            List<ScheduleItem> matches = schedules.listSchedules(null).stream()
                    .filter(item -> !isTerminal(item))
                    .filter(item -> normalize(item.getTitle()).equals(normalize(title)))
                    .toList();
            if (matches.size() == 1) return Optional.of(matches.getFirst());
        }
        return Optional.empty();
    }

    private static boolean isMergeRequest(String text, ConversationSnapshot snapshot) {
        boolean refersToTwo = containsAny(text, "前兩個", "前兩筆", "這兩個", "這兩筆")
                || orderedPair(text, "第一", "第二")
                || orderedPair(text, "第1", "第2")
                || orderedPair(text, "1", "2");
        return refersToTwo && (text.contains("行程") || snapshot.lastScheduleListIds().size() >= 2)
                && (text.contains("合併") || text.contains("合成"));
    }

    private static Integer keepOrdinal(String text, String lastAssistant) {
        if (lastAssistant == null || !lastAssistant.contains("保留第一個")
                || !lastAssistant.contains("保留第二個")) return null;
        if (containsAny(text, "照建議", "按建議", "照你建議")) {
            if (lastAssistant.contains("建議保留第一筆")) return 1;
            if (lastAssistant.contains("建議保留第二筆")) return 2;
        }
        if (containsAny(text, "保留第一", "留第一", "第一個留", "第一筆留", "選第一", "選1",
                "留1", "就第一")) return 1;
        if (containsAny(text, "保留第二", "留第二", "第二個留", "第二筆留", "選第二", "選2",
                "留2", "就第二")) return 2;
        return null;
    }

    private static boolean orderedPair(String text, String first, String second) {
        int firstIndex = text.indexOf(first);
        int secondIndex = text.indexOf(second);
        return firstIndex >= 0 && secondIndex > firstIndex;
    }

    private static int completeness(ScheduleItem item) {
        int score = item.getStatus() == ScheduleStatus.CONFIRMED ? 4 : 0;
        if (item.getPlaceId() != null) score += 2;
        if (item.getResponsiblePerson() != null && !item.getResponsiblePerson().isBlank()) score++;
        if (item.getCategory() != null && item.getCategory() != ScheduleItem.Category.UNKNOWN) score++;
        if (item.getRecurrence() != null && item.getRecurrence() != ScheduleItem.Recurrence.NONE) score++;
        if (item.isEndTimeExplicit()) score++;
        return score;
    }

    private static boolean sameDetails(ScheduleItem first, ScheduleItem second) {
        return normalize(first.getTitle()).equals(normalize(second.getTitle()))
                && first.getStartAt().equals(second.getStartAt())
                && first.getEndAt().equals(second.getEndAt())
                && java.util.Objects.equals(first.getPlaceId(), second.getPlaceId())
                && java.util.Objects.equals(first.getResponsiblePerson(), second.getResponsiblePerson())
                && first.getRecurrence() == second.getRecurrence()
                && java.util.Objects.equals(first.getRecurrenceUntil(), second.getRecurrenceUntil());
    }

    private static Integer deleteOrdinal(String text) {
        Matcher matcher = ORDINAL_DELETE.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static boolean isQuotedScheduleDiscard(String text, String interpretationText) {
        boolean deleting = containsAny(text, "刪除這個行程", "刪掉這個行程", "取消這個行程",
                "刪除這個草稿", "刪掉這個草稿", "放棄這個草稿");
        return deleting && interpretationText != null
                && interpretationText.contains("【LINE 明確引用】")
                && interpretationText.contains("行程");
    }

    private static boolean isTerminal(ScheduleItem item) {
        return item.getStatus() == ScheduleStatus.CANCELED
                || item.getStatus() == ScheduleStatus.REJECTED
                || item.getStatus() == ScheduleStatus.COMPLETED;
    }

    private static String line(ScheduleItem item) {
        return "行程（%s）「%s」｜%s–%s".formatted(
                statusLabel(item.getStatus()), item.getTitle(),
                TIME.format(item.getStartAt().atZone(TAIPEI)),
                DateTimeFormatter.ofPattern("HH:mm").format(item.getEndAt().atZone(TAIPEI)));
    }

    private static String discardedLabel(ScheduleItem item) {
        return item.getStatus() == ScheduleStatus.PROPOSED
                ? "已放棄待確認行程「%s」".formatted(item.getTitle())
                : "已取消行程「%s」".formatted(item.getTitle());
    }

    private static String statusLabel(ScheduleStatus status) {
        return switch (status) {
            case PROPOSED, PENDING -> "待確認";
            case CONFIRMED -> "已確認";
            case CANCELED -> "已取消";
            case REJECTED -> "已放棄";
            case COMPLETED -> "已完成";
        };
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s　「」『』:：，,。！？?]", "").toLowerCase();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
