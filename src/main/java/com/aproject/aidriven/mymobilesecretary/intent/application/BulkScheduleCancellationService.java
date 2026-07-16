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

    public BulkScheduleCancellationService(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
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

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(RANGE);
    }
}
