package com.aproject.aidriven.mymobilesecretary.schedule.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** ScheduleItem 資料存取。 */
public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {

    List<ScheduleItem> findAllByOrderByStartAtAsc();

    List<ScheduleItem> findByStatusOrderByStartAtAsc(ScheduleStatus status);
}
