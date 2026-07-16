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

        List<Occurrence> completeOverview = Stream.concat(fixed.stream(), oneTime.stream())
                .sorted(Comparator.comparing(Occurrence::startAt))
                .toList();
        StringBuilder message = new StringBuilder("📅 %s行程總覽：".formatted(date.format(DAY)));
        appendCompleteOverview(message, completeOverview);

        List<ContainedOccurrence> contained = oneTime.stream()
                .flatMap(child -> fixed.stream()
                        .filter(parent -> contains(parent, child))
                        .map(parent -> new ContainedOccurrence(parent, child)))
                .toList();
        if (!contained.isEmpty()) {
            message.append("\n\n📝 已位於固定行程內的當日項目：");
            contained.forEach(relation -> message.append(
                    "\n- %s %s｜%s，位於 %s–%s「%s」內，不需要改期。"
                            .formatted(scheduleEmoji(relation.child().source()), range(relation.child()),
                                    relation.child().source().getTitle(),
                                    time(relation.parent().startAt()), time(relation.parent().endAt()),
                                    relation.parent().source().getTitle())));
        }

        fixed.stream()
                .map(Occurrence::source)
                .filter(item -> item.getStatus() == ScheduleStatus.PROPOSED)
                .findFirst()
                .ifPresent(item -> {
                    contextService.rememberSchedule(item);
                    message.append("\n\n❓ 請確認是否把上述當日項目併入固定行程「%s」；我不會自行確認。"
                            .formatted(item.getTitle()));
                });
        return IntentResult.message(IntentResult.Action.SCHEDULES_LISTED, message.toString());
    }

    /**
     * 使用者拒絕把當日項目併入固定行程(「簡報排練不要併到上班固定行程」)。
     * 不自行更動任何行程狀態:只把目標當獨立行程回報時段,改期或維持原時間由使用者決定
     * (使用者明確要求系統不可代替他確定行程)。
     */
    public IntentResult rejectMerge(String text) {
        List<ScheduleItem> visible = scheduleService.listSchedules(null).stream()
                .filter(item -> VISIBLE_STATUSES.contains(item.getStatus()))
                .filter(item -> item.getStatus() != ScheduleStatus.COMPLETED)
                .toList();
        ScheduleItem target = oneTimeMentionedIn(visible, text)
                .or(() -> lastContextOneTime())
                .or(() -> soleNestedOneTime(visible))
                .orElse(null);
        if (target == null) {
            return IntentResult.clarificationNeeded(
                    "我知道你不要併入，但我不確定是指哪個行程；請連行程名稱一起說（例如「簡報排練不要併入」）。");
        }
        // 記住目標,讓「改到八點」「維持原時間」等後續句子能接得上
        contextService.rememberSchedule(target);
        LocalDate date = LocalDate.ofInstant(target.getStartAt(), TAIPEI);
        String container = visible.stream()
                .filter(item -> item.getRecurrence() != ScheduleItem.Recurrence.NONE)
                .filter(item -> occursOn(item, date))
                .filter(item -> contains(project(item, date),
                        new Occurrence(target, target.getStartAt(), target.getEndAt())))
                .findFirst()
                .map(item -> "固定行程「%s」".formatted(item.getTitle()))
                .orElse("固定行程");
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                ("好，「%s」不會併入%s，我把它當獨立行程處理：目前排在 %s %s–%s。\n"
                        + "要改期就直接說新時間；回「維持原時間」我就照原時段確認。不會由我代你決定。")
                        .formatted(target.getTitle(), container, date.format(DAY),
                                time(target.getStartAt()), time(target.getEndAt())));
    }

    /** 句子裡點名的單次行程(取標題最長的命中,避免短標題誤搶)。 */
    private static java.util.Optional<ScheduleItem> oneTimeMentionedIn(
            List<ScheduleItem> visible, String text) {
        String normalized = normalizeForMatch(text);
        return visible.stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(item -> {
                    String title = normalizeForMatch(item.getTitle());
                    return title.length() >= 2 && normalized.contains(title);
                })
                .max(Comparator.comparingInt(item -> normalizeForMatch(item.getTitle()).length()));
    }

    /** 沒點名時退回對話上下文;只接受單次行程,固定行程本身不是「不要併入」的對象。 */
    private java.util.Optional<ScheduleItem> lastContextOneTime() {
        Long id = contextService.scheduleIdAt(null);
        if (id == null) {
            return java.util.Optional.empty();
        }
        ScheduleItem item = scheduleService.getSchedule(id);
        return item != null && item.getRecurrence() == ScheduleItem.Recurrence.NONE
                ? java.util.Optional.of(item)
                : java.util.Optional.empty();
    }

    /** 最後手段:全系統只有一個「位於固定行程內」的單次行程時,不必再回問。 */
    private static java.util.Optional<ScheduleItem> soleNestedOneTime(List<ScheduleItem> visible) {
        List<ScheduleItem> nested = visible.stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.NONE)
                .filter(child -> visible.stream()
                        .filter(parent -> parent.getRecurrence() != ScheduleItem.Recurrence.NONE)
                        .anyMatch(parent -> {
                            LocalDate date = LocalDate.ofInstant(child.getStartAt(), TAIPEI);
                            return occursOn(parent, date) && contains(project(parent, date),
                                    new Occurrence(child, child.getStartAt(), child.getEndAt()));
                        }))
                .toList();
        return nested.size() == 1 ? java.util.Optional.of(nested.get(0)) : java.util.Optional.empty();
    }

    private static String normalizeForMatch(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s「」『』:：，,。！!？?]", "").toLowerCase(Locale.TAIWAN);
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

    /** 第一區先按時間列出完整行程；固定行程用圖釘，單次行程依內容選圖示。 */
    private static void appendCompleteOverview(StringBuilder message, List<Occurrence> occurrences) {
        occurrences.forEach(occurrence -> message.append("\n\n%s %s"
                        .formatted(scheduleEmoji(occurrence.source()), range(occurrence)))
                .append("\n- %s｜%s".formatted(occurrence.source().getTitle(),
                        statusLabel(occurrence.source().getStatus()))));
    }

    static String scheduleEmoji(ScheduleItem item) {
        if (item.getRecurrence() != ScheduleItem.Recurrence.NONE) {
            return "📌";
        }
        String title = normalizeForMatch(item.getTitle());
        if (containsAny(title, "簡報", "電腦", "線上", "視訊", "文件")) return "💻";
        if (containsAny(title, "會議", "週會", "工作", "專案", "上班")) return "💼";
        if (containsAny(title, "運動", "健身", "跑步", "慢跑", "重訓", "游泳", "瑜伽")) return "🏃";
        if (containsAny(title, "吃飯", "午餐", "晚餐", "早餐", "聚餐", "餐廳")) return "🍽️";
        if (containsAny(title, "看醫生", "醫院", "診所", "回診", "牙醫")) return "🏥";
        if (containsAny(title, "接送", "接小孩", "送小孩", "通勤", "開車", "搭車")) return "🚗";
        if (containsAny(title, "上課", "課程", "讀書", "學習")) return "📚";
        if (containsAny(title, "購物", "採買", "買東西")) return "🛒";
        if (containsAny(title, "睡覺", "休息", "午睡")) return "🛌";
        return "📅";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean occursOn(ScheduleItem item, LocalDate date) {
        LocalDate anchor = LocalDate.ofInstant(item.getStartAt(), TAIPEI);
        if (date.isBefore(anchor)
                || (item.getRecurrenceUntil() != null && date.isAfter(item.getRecurrenceUntil()))) {
            return false;
        }
        return switch (item.getRecurrence()) {
            case NONE -> false;
            case WEEKLY -> date.getDayOfWeek() == anchor.getDayOfWeek();
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
