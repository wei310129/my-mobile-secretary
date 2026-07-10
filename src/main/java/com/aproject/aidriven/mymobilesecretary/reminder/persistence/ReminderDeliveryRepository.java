package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderDelivery;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** ReminderDelivery 資料存取。 */
public interface ReminderDeliveryRepository extends JpaRepository<ReminderDelivery, Long> {

    List<ReminderDelivery> findByReminderId(Long reminderId);
}
