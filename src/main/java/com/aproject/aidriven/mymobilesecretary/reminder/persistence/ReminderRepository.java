package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reminder 資料存取。 */
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByTaskId(Long taskId);

    /** debounce 用:任務在某時點之後是否已觸發過提醒。 */
    boolean existsByTaskIdAndTriggeredAtAfter(Long taskId, Instant threshold);
}
