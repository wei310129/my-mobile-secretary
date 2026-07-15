package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 升級催促:提醒送出後若逾時未確認,重新通知並排入下一輪,最多催 maxEscalations 次。
 *
 * 放行條件(任一不符就靜默丟棄,不是錯誤):
 * 1. 提醒還在(未被確認)。
 * 2. 任務仍可升級(REMINDED/ESCALATED;已確認/取消的任務不催)。
 */
@Service
@Transactional
public class ReminderEscalationService {

    private static final Logger log = LoggerFactory.getLogger(ReminderEscalationService.class);

    private final ReminderRepository reminderRepository;
    private final TaskRepository taskRepository;
    private final ReminderDeliveryService deliveryService;
    private final ReminderScheduleService scheduleService;
    private final ReminderPreferenceService preferenceService;
    private final ReminderProperties properties;
    private final Clock clock;

    public ReminderEscalationService(ReminderRepository reminderRepository,
                                     TaskRepository taskRepository,
                                     ReminderDeliveryService deliveryService,
                                     ReminderScheduleService scheduleService,
                                     ReminderPreferenceService preferenceService,
                                     ReminderProperties properties,
                                     Clock clock) {
        this.reminderRepository = reminderRepository;
        this.taskRepository = taskRepository;
        this.deliveryService = deliveryService;
        this.scheduleService = scheduleService;
        this.preferenceService = preferenceService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 執行第 attempt 次升級催促。
     *
     * @return 升級後的提醒;被放行條件擋下時回傳 empty(閉環已完成,不需催)
     */
    public Optional<Reminder> escalate(Long reminderId, int attempt) {
        Reminder reminder = reminderRepository.findById(reminderId).orElse(null);
        // 提醒已確認(或已刪)→ 閉環完成,催促鏈到此為止
        if (reminder == null || reminder.getStatus() == ReminderStatus.CONFIRMED) {
            return Optional.empty();
        }

        Task task = taskRepository.findById(reminder.getTaskId()).orElse(null);
        // 任務已確認/取消 → 不催(使用者可能只確認了任務、沒確認提醒)
        if (task == null || !task.canBeEscalated()) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        Optional<Instant> deferred = preferenceService.deferUntil(task, now);
        if (deferred.isPresent()) {
            scheduleService.scheduleEscalation(reminderId, attempt, deferred.get());
            return Optional.empty();
        }
        reminder.escalate(now);
        task.escalate(now);

        deliveryService.deliver(reminder, task,
                "第 %d 次催促(最多 %d 次):%s".formatted(attempt, properties.maxEscalations(), reminder.getTriggerReason()));

        // 還沒催滿就排下一輪;catch 滿了就停,避免變成騷擾
        if (attempt < properties.maxEscalations()) {
            scheduleService.scheduleEscalation(reminderId, attempt + 1, now.plus(properties.escalationInterval()));
        } else {
            log.info("Escalation chain exhausted [reminder={} attempts={}]", reminderId, attempt);
        }
        return Optional.of(reminder);
    }
}
