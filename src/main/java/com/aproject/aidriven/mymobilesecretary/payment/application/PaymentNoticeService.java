package com.aproject.aidriven.mymobilesecretary.payment.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.payment.domain.PaymentNotice;
import com.aproject.aidriven.mymobilesecretary.payment.domain.PaymentNotice.Status;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.PaymentNoticeRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.FlexibleDayTaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCanceledEvent;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCompletedEvent;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan.SourceKind;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.FlexibleDayTaskPlanRepository;
import com.aproject.aidriven.mymobilesecretary.shared.time.ChineseTimePeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captures unpaid notices and turns an explicit lead-time request into a safe reminder task. */
@Service
@Transactional
public class PaymentNoticeService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern LEAD_PREFIX = Pattern.compile(
            "(?:提前|到期前|期限前)\\s*([0-9零〇一二兩三四五六七八九十百]+)\\s*天");
    private static final Pattern LEAD_SUFFIX = Pattern.compile(
            "([0-9零〇一二兩三四五六七八九十百]+)\\s*天前(?:提醒|通知)?");
    private static final Pattern TIME = Pattern.compile(
            ChineseTimePeriod.CAPTURING_REGEX
                    + "?\\s*(\\d{1,2})\\s*(?:點|:)\\s*(\\d{1,2})?\\s*分?");

    private final PaymentNoticeRepository repository;
    private final FlexibleDayTaskPlanRepository planRepository;
    private final FlexibleDayTaskService flexibleDayTaskService;
    private final UniversalLifeRecordService lifeRecordService;
    private final Clock clock;

    public PaymentNoticeService(PaymentNoticeRepository repository,
                                FlexibleDayTaskPlanRepository planRepository,
                                FlexibleDayTaskService flexibleDayTaskService,
                                UniversalLifeRecordService lifeRecordService,
                                Clock clock) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.flexibleDayTaskService = flexibleDayTaskService;
        this.lifeRecordService = lifeRecordService;
        this.clock = clock;
    }

    public CaptureResult capture(String title, String issuer, Integer amountTwd,
                                 LocalDate dueDate) {
        if (dueDate == null) {
            throw new IllegalArgumentException("payment notice due date is required");
        }
        String safeTitle = title == null || title.isBlank() ? "繳費通知" : title.strip();
        Instant now = Instant.now(clock);
        PaymentNotice saved = repository.save(PaymentNotice.pending(
                safeTitle, issuer, amountTwd, dueDate, now));
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.REMINDER,
                "收到繳費通知：" + safeTitle, now,
                List.of("繳費通知", "待繳費", safeTitle));
        return new CaptureResult(saved.getId(), safeTitle, issuer, amountTwd, dueDate);
    }

    /** Deterministic continuation for phrases such as "到期前三天提醒我". */
    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        LeadRequest request = parseLeadRequest(text);
        if (request == null) return Optional.empty();
        Optional<PaymentNotice> pending = repository
                .findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId(),
                        Status.PENDING_REMINDER);
        if (pending.isEmpty()) return Optional.empty();

        PaymentNotice notice = pending.get();
        LocalDate reminderDate = notice.getDueDate().minusDays(request.days());
        if (reminderDate.isAfter(notice.getDueDate())) {
            return Optional.of(IntentResult.clarificationNeeded("提醒日不能晚於繳費期限。"));
        }
        try {
            beforeMutation.run();
            String description = "繳費期限：%s%s%s。這是提醒待辦，不會自動付款。".formatted(
                    notice.getDueDate(),
                    notice.getIssuer() == null ? "" : "；收款單位：" + notice.getIssuer(),
                    notice.getAmountTwd() == null ? "" : "；應繳金額：NT$ %,d".formatted(
                            notice.getAmountTwd()));
            var planned = flexibleDayTaskService.plan(
                    "繳費：" + notice.getTitle(), description, notice.getDueDate(),
                    reminderDate, request.time(), SourceKind.PAYMENT_NOTICE);
            notice.schedule(request.days(), planned.plan().getId(), Instant.now(clock));
            repository.save(notice);
            String source = planned.scheduleSuggested()
                    ? "我依目前已知行程選了第一個可用空檔"
                    : "已使用你指定的時間";
            String message = "已建立「%s」的全天彈性待辦；它不會把整天標成忙碌，也不會自動付款。\n"
                    + "繳費期限：%s；%s，提醒時間：%s。\n"
                    + "若你提早完成，只要回報「%s繳好了」，Java 任務狀態會關閉，後續提醒不會再送出。";
            return Optional.of(IntentResult.message(IntentResult.Action.TASK_CREATED,
                    message.formatted(notice.getTitle(), notice.getDueDate(), source,
                            DATE_TIME.format(planned.plan().getRemindAt().atZone(TAIPEI)),
                            notice.getTitle())));
        } catch (IllegalArgumentException invalidPlan) {
            return Optional.of(IntentResult.clarificationNeeded(invalidPlan.getMessage()));
        }
    }

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        planRepository.findByTaskId(event.taskId())
                .flatMap(plan -> repository.findByFlexiblePlanId(plan.getId()))
                .ifPresent(notice -> notice.complete(event.completedAt()));
    }

    @EventListener
    public void onTaskCanceled(TaskCanceledEvent event) {
        planRepository.findByTaskId(event.taskId())
                .flatMap(plan -> repository.findByFlexiblePlanId(plan.getId()))
                .ifPresent(notice -> notice.cancel(event.canceledAt()));
    }

    static LeadRequest parseLeadRequest(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = LEAD_PREFIX.matcher(text);
        if (!matcher.find()) {
            matcher = LEAD_SUFFIX.matcher(text);
            if (!matcher.find()) return null;
        }
        int days = chineseNumber(matcher.group(1));
        if (days < 0 || days > 365) {
            throw new IllegalArgumentException("繳費提醒提前天數必須介於 0 到 365 天");
        }
        return new LeadRequest(days, parseTime(text));
    }

    private static LocalTime parseTime(String text) {
        Matcher matcher = TIME.matcher(text);
        if (!matcher.find()) return null;
        String period = matcher.group(1);
        int hour = Integer.parseInt(matcher.group(2));
        int minute = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        hour = ChineseTimePeriod.toTwentyFourHour(period, hour);
        if (hour > 23 || minute > 59) {
            throw new IllegalArgumentException("提醒時間格式不合理");
        }
        return LocalTime.of(hour, minute);
    }

    private static int chineseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        int total = 0;
        int current = 0;
        for (char character : value.toCharArray()) {
            int digit = switch (character) {
                case '零', '〇' -> 0;
                case '一' -> 1;
                case '二', '兩' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                current = digit;
            } else if (character == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (character == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                throw new IllegalArgumentException("無法解析提醒提前天數");
            }
        }
        return total + current;
    }

    public record CaptureResult(Long id, String title, String issuer,
                                Integer amountTwd, LocalDate dueDate) {
    }

    record LeadRequest(int days, LocalTime time) {
    }
}
