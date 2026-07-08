package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reminder 資料存取。 */
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByTaskId(Long taskId);
}
