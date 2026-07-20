package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 批次刪除行程的安全閘(使用者 2026-07-16 裁決 #49):
 * 破壞性操作一次只能刪「指定時間段內的非固定行程」;
 * 固定(重複)行程不可批次刪,只能一個一個指名;沒給時間段一律先問,不可全刪。
 */
@Service
public class BulkScheduleCancellationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final EnumSet<ScheduleStatus> CANCELABLE = EnumSet.of(
            ScheduleStatus.PROPOSED, ScheduleStatus.CONFIRMED, ScheduleStatus.PENDING);

    private final ScheduleService scheduleService;
    private final ConversationContextService contextService;

    public BulkScheduleCancellationService(
            ScheduleService scheduleService, ConversationContextService contextService) {
        this.scheduleService = scheduleService;
        this.contextService = contextService;
    }

    /**
     * 預覽指定範圍內明確分類為 PERSONAL 的非固定行程；WORK、FAMILY、UNKNOWN 與固定行程
     * 一律排除。候選 id 只保存於目前 actor 的短期對話上下文，這一輪絕不刪除。
     */
    public IntentResult previewPrivateWithin(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return IntentResult.clarificationNeeded(
                    "請先給要預覽的明確時間範圍；我不會直接刪除任何行程。");
        }
        List<ScheduleItem> activeInRange = scheduleService.listSchedules(null).stream()
                .filter(item -> CANCELABLE.contains(item.getStatus()))
                .filter(item -> !item.getStartAt().isBefore(from) && item.getStartAt().isBefore(to))
                .toList();
        List<ScheduleItem> candidates = activeInRange.stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(item -> item.getCategory() == ScheduleItem.Category.PERSONAL)
                .toList();
        List<ScheduleItem> excluded = activeInRange.stream()
                .filter(item -> !candidates.contains(item))
                .toList();
        contextService.rememberScheduleList(candidates);

        String range = "%s–%s".formatted(format(from), format(to));
        String candidateLines = candidates.isEmpty()
                ? "（沒有可安全判定為私人的非固定行程）"
                : candidates.stream().map(item -> "- 「%s」%s".formatted(
                                item.getTitle(), format(item.getStartAt())))
                        .collect(java.util.stream.Collectors.joining("\n"));
        String excludedLines = excluded.isEmpty()
                ? "（無）"
                : excluded.stream().map(BulkScheduleCancellationService::excludedLine)
                        .collect(java.util.stream.Collectors.joining("\n"));
        String confirmation = candidates.isEmpty()
                ? "\n\n這一輪沒有可刪候選，所以我什麼都沒動。"
                : "\n\n目前尚未刪除。若清單正確，請明確回覆「確認刪除剛才清單」。";
        return IntentResult.message(IntentResult.Action.SCHEDULE_CANCELLATION_PREVIEWED,
                "%s 私人非固定行程刪除預覽：\n%s\n\n已排除：\n%s%s"
                        .formatted(range, candidateLines, excludedLines, confirmation));
    }

    /** 第二輪確認：只刪除上一輪 actor-private 預覽中的 id，且執行前重新驗證安全條件。 */
    public IntentResult confirmPreview(List<Long> previewIds) {
        if (previewIds == null || previewIds.isEmpty()) {
            return IntentResult.clarificationNeeded("目前沒有待確認的批次刪除清單。");
        }
        List<ScheduleItem> safe = previewIds.stream().distinct()
                .map(scheduleService::getSchedule)
                .filter(item -> CANCELABLE.contains(item.getStatus()))
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(item -> item.getCategory() == ScheduleItem.Category.PERSONAL)
                .toList();
        safe.forEach(item -> scheduleService.cancelSchedule(item.getId()));
        if (safe.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_BULK_CANCELED,
                    "預覽清單已失效或不再符合安全條件，所以我什麼都沒刪。");
        }
        String lines = safe.stream()
                .map(item -> "- 「%s」%s".formatted(item.getTitle(), format(item.getStartAt())))
                .collect(java.util.stream.Collectors.joining("\n"));
        int skipped = previewIds.size() - safe.size();
        String skippedNote = skipped <= 0 ? "" : "\n另有 %d 筆因狀態或分類改變而未刪除。".formatted(skipped);
        return IntentResult.message(IntentResult.Action.SCHEDULES_BULK_CANCELED,
                "已依剛才確認的預覽刪除 %d 個私人非固定行程：\n%s%s"
                        .formatted(safe.size(), lines, skippedNote));
    }

    /** 有明確範圍才動手;刪了什麼、留了什麼(固定行程)都要回報清楚。 */
    public IntentResult cancelWithin(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return IntentResult.clarificationNeeded(
                    "批次刪除是不可回復的操作,我只會刪「指定時間段內的非固定行程」:"
                            + "\n請給我明確範圍,例如「刪掉下週一到週三的行程」。"
                            + "\n固定行程不會被批次刪,要刪請一個一個跟我說(例:「取消每週的簡報排練」)。");
        }
        List<ScheduleItem> active = scheduleService.listSchedules(null).stream()
                .filter(item -> CANCELABLE.contains(item.getStatus()))
                .toList();
        List<ScheduleItem> oneTimeInRange = active.stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(item -> !item.getStartAt().isBefore(from) && item.getStartAt().isBefore(to))
                .toList();
        List<ScheduleItem> recurringKept = active.stream()
                .filter(item -> item.getRecurrence() != ScheduleItem.Recurrence.NONE)
                .toList();

        String range = "%s–%s".formatted(format(from), format(to));
        if (oneTimeInRange.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_BULK_CANCELED,
                    "%s 之間沒有可刪的非固定行程,所以我什麼都沒動。%s"
                            .formatted(range, recurringNote(recurringKept)));
        }
        oneTimeInRange.forEach(item -> scheduleService.cancelSchedule(item.getId()));
        StringBuilder message = new StringBuilder(
                "已刪除 %s 之間的 %d 個非固定行程:".formatted(range, oneTimeInRange.size()));
        oneTimeInRange.forEach(item -> message.append("\n「%s」%s".formatted(
                item.getTitle(), format(item.getStartAt()))));
        message.append(recurringNote(recurringKept));
        return IntentResult.message(IntentResult.Action.SCHEDULES_BULK_CANCELED, message.toString());
    }

    /** 固定行程被保護的事實一定要講,否則使用者會以為「都刪了」。 */
    private static String recurringNote(List<ScheduleItem> recurringKept) {
        if (recurringKept.isEmpty()) {
            return "";
        }
        String titles = recurringKept.stream()
                .map(item -> "「%s」".formatted(item.getTitle()))
                .collect(java.util.stream.Collectors.joining("、"));
        return "\n\n固定行程 %s 我沒有動:這類要一個一個指名刪,避免誤刪整條規則。"
                .formatted(titles);
    }

    private static String excludedLine(ScheduleItem item) {
        String reason = item.getRecurrence() != ScheduleItem.Recurrence.NONE
                ? "固定行程"
                : switch (item.getCategory()) {
                    case WORK -> "工作行程";
                    case FAMILY -> "家庭／小孩行程";
                    case UNKNOWN -> "未明確分類";
                    case PERSONAL -> "不符合安全條件";
                };
        return "- 「%s」%s（%s，保留）".formatted(
                item.getTitle(), format(item.getStartAt()), reason);
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(RANGE);
    }
}
