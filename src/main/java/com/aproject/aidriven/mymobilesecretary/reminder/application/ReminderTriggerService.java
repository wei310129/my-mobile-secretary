package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒觸發:geofence 命中(之後還有定時排程)之後,決定要不要真的提醒。
 *
 * 兩道守門(順序重要):
 * 1. 任務狀態:已 CONFIRMED/CANCELED 的任務一律不提醒。
 * 2. debounce:視窗內(預設 10 分鐘)已提醒過的任務不重複提醒,
 *    避免在店門口走來走去被連續轟炸。
 */
@Service
@Transactional
public class ReminderTriggerService {

    private final TaskRepository taskRepository;
    private final ReminderRepository reminderRepository;
    private final ReminderDeliveryService deliveryService;
    private final ReminderScheduleService scheduleService;
    private final ReminderProperties properties;
    private final Clock clock;

    public ReminderTriggerService(TaskRepository taskRepository,
                                  ReminderRepository reminderRepository,
                                  ReminderDeliveryService deliveryService,
                                  ReminderScheduleService scheduleService,
                                  ReminderProperties properties,
                                  Clock clock) {
        this.taskRepository = taskRepository;
        this.reminderRepository = reminderRepository;
        this.deliveryService = deliveryService;
        this.scheduleService = scheduleService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 嘗試為任務觸發一次提醒。
     *
     * @param reason 觸發原因(進推播文字與除錯),例如 "ENTER geofence: 全聯"
     * @return 建立的提醒;被狀態或 debounce 擋下時回傳 empty(不是錯誤)
     */
    public Optional<Reminder> tryTrigger(Long taskId, String reason) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Task", taskId));

        // 守門 1:終止狀態的任務不再提醒
        if (!task.canBeReminded()) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);

        // 守門 2:debounce——視窗內已提醒過就跳過
        Instant windowStart = now.minus(properties.debounceWindow());
        if (reminderRepository.existsByTaskIdAndTriggeredAtAfter(taskId, windowStart)) {
            return Optional.empty();
        }

        task.remind(now);
        Reminder reminder = reminderRepository.save(Reminder.triggered(taskId, reason, now));

        // 立即送出到所有通道;送出失敗只會被記錄,不影響提醒本身
        deliveryService.deliver(reminder, task);

        // 排入第一次升級催促:逾時未確認就再提醒(追蹤到底,秘書與鬧鐘的差別)
        scheduleService.scheduleEscalation(reminder.getId(), 1, now.plus(properties.escalationInterval()));
        return Optional.of(reminder);
    }
}
