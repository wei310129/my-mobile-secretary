package com.aproject.aidriven.mymobilesecretary.schedule.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcome;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** ScheduleOutcome 資料存取。 */
public interface ScheduleOutcomeRepository extends JpaRepository<ScheduleOutcome, Long> {

    Optional<ScheduleOutcome> findByScheduleItemId(Long scheduleItemId);

    boolean existsByScheduleItemId(Long scheduleItemId);
}
