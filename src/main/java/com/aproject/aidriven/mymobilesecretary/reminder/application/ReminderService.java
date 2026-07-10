package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒查詢與確認 use case。
 */
@Service
@Transactional
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final Clock clock;

    public ReminderService(ReminderRepository reminderRepository, Clock clock) {
        this.reminderRepository = reminderRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Reminder> listReminders() {
        return reminderRepository.findAllByOrderByTriggeredAtDesc();
    }

    /** 查單一提醒;不存在丟 NotFoundException(404)。 */
    @Transactional(readOnly = true)
    public Reminder getReminder(Long reminderId) {
        return reminderRepository.findById(reminderId)
                .orElseThrow(() -> new NotFoundException("Reminder", reminderId));
    }

    /** 確認提醒已處理;重複確認由 domain 丟 422。 */
    public Reminder confirmReminder(Long reminderId) {
        Reminder reminder = getReminder(reminderId);
        reminder.confirm(Instant.now(clock));
        return reminder;
    }
}
