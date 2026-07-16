package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** 將固定行程與指定日期的單次行程合併成可讀的每日總覽。 */
@Service
public class DailyScheduleOverviewService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern(
            "yyyy/MM/dd（E）", Locale.TAIWAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final EnumSet<ScheduleStatus> VISIBLE_STATUSES = EnumSet.of(
            ScheduleStatus.PROPOSED, ScheduleStatus.CONFIRMED,
            ScheduleStatus.PENDING, ScheduleStatus.COMPLETED);

    private final ScheduleService scheduleService;
    private final ConversationContextService contextService;

    public DailyScheduleOverviewService(ScheduleService scheduleService,
                                        ConversationContextService contextService) {
        this.scheduleService = scheduleService;
        this.contextService = contextService;
    }

    public IntentResult overview(LocalDate date) {
        List<ScheduleItem> visible = scheduleService.listSchedules(null).stream()
                .filter(item -> VISIBLE_STATUSES.contains(item.getStatus()))
                .toList();
        List<Occurrence> fixed = visible.stream()
                .filter(item -> item.getRecurrence() != ScheduleItem.Recurrence.NONE)
                .filter(item -> occursOn(item, date))
                .map(item -> project(item, date))
                .sorted(Comparator.comparing(Occurrence::startAt))
                .toList();
        List<Occurrence> oneTime = visible.stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(item -> LocalDate.ofInstant(item.getStartAt(), TAIPEI).equals(date))
                .map(item -> new Occurrence(item, item.getStartAt(), item.getEndAt()))
                .sorted(Comparator.comparing(Occurrence::startAt))
                .toList();

        List<ScheduleItem> sources = Stream.concat(fixed.stream(), oneTime.stream())
                .map(Occurrence::source).distinct().toList();
        contextService.rememberScheduleList(sources);

        if (fixed.isEmpty() && oneTime.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_LISTED,
                    "%s目前沒有固定或當日行程。".formatted(date.format(DAY)));
        }

        StringBuilder message = new StringBuilder("%s行程總覽:".formatted(date.format(DAY)));
        appendSection(message, "固定行程", fixed);
        appendSection(message, "當日行程", oneTime);

        List<ContainedOccurrence> contained = oneTime.stream()
                .flatMap(child -> fixed.stream()
                        .filter(parent -> contains(parent, child))
                        .map(parent -> new ContainedOccurrence(parent, child)))
                .toList();
        if (!contained.isEmpty()) {
            message.append("\n\n已位於固定行程內的當日項目:");
            contained.forEach(relation -> message.append("\n%s「%s」位於 %s–%s「%s」內，不需要改期。"
                    .formatted(range(relation.child()), relation.child().source().getTitle(),
                            time(relation.parent().startAt()), time(relation.parent().endAt()),
                            relation.parent().source().getTitle())));
        }

        fixed.stream()
                .map(Occurrence::source)
                .filter(item -> item.getStatus() == ScheduleStatus.PROPOSED)
                .findFirst()
                .ifPresent(item -> {
                    contextService.rememberSchedule(item);
                    message.append("\n\n請確認是否把上述當日項目併入固定行程「%s」；我不會自行確認。"
                            .formatted(item.getTitle()));
                });
        return IntentResult.message(IntentResult.Action.SCHEDULES_LISTED, message.toString());
    }

    public IntentResult confirmMerge() {
        Long scheduleId = contextService.scheduleIdAt(null);
        if (scheduleId == null) {
            return IntentResult.clarificationNeeded("目前沒有等待確認併入的固定行程。");
        }
        ScheduleItem item = scheduleService.getSchedule(scheduleId);
        if (item.getStatus() != ScheduleStatus.PROPOSED
                || item.getRecurrence() == ScheduleItem.Recurrence.NONE) {
            return IntentResult.clarificationNeeded("目前指到的不是等待確認的固定行程，請先查看當日行程總覽。");
        }
        scheduleService.confirmSchedule(scheduleId);
        contextService.rememberSchedule(item);
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "已確認固定行程「%s」。位於其中的當日行程會分項列出，不會要求改期。"
                        .formatted(item.getTitle()));
    }

    private static void appendSection(StringBuilder message, String title, List<Occurrence> occurrences) {
        if (occurrences.isEmpty()) {
            return;
        }
        message.append("\n\n").append(title).append(":");
        occurrences.forEach(occurrence -> message.append("\n%s｜%s｜%s"
                .formatted(range(occurrence), occurrence.source().getTitle(),
                        statusLabel(occurrence.source().getStatus()))));
    }

    private static boolean occursOn(ScheduleItem item, LocalDate date) {
        return switch (item.getRecurrence()) {
            case NONE -> false;
            case WEEKLY -> {
                LocalDate anchor = LocalDate.ofInstant(item.getStartAt(), TAIPEI);
                yield !date.isBefore(anchor) && date.getDayOfWeek() == anchor.getDayOfWeek();
            }
            case WEEKDAYS -> date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
        };
    }

    private static Occurrence project(ScheduleItem item, LocalDate date) {
        ZonedDateTime sourceStart = ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI);
        Instant projectedStart = date.atTime(sourceStart.toLocalTime()).atZone(TAIPEI).toInstant();
        return new Occurrence(item, projectedStart,
                projectedStart.plus(Duration.between(item.getStartAt(), item.getEndAt())));
    }

    private static boolean contains(Occurrence parent, Occurrence child) {
        return !child.startAt().isBefore(parent.startAt())
                && !child.endAt().isAfter(parent.endAt());
    }

    private static String range(Occurrence occurrence) {
        return "%s–%s".formatted(time(occurrence.startAt()), time(occurrence.endAt()));
    }

    private static String time(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(TIME);
    }

    private static String statusLabel(ScheduleStatus status) {
        return switch (status) {
            case PROPOSED -> "待確認";
            case CONFIRMED -> "已確認";
            case PENDING -> "待安排";
            case COMPLETED -> "已完成";
            case REJECTED -> "已放棄";
            case CANCELED -> "已取消";
        };
    }

    record Occurrence(ScheduleItem source, Instant startAt, Instant endAt) {
    }

    private record ContainedOccurrence(Occurrence parent, Occurrence child) {
    }
}
