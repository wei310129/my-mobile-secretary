package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlexibleDayTaskPlanRepository extends JpaRepository<FlexibleDayTaskPlan, Long> {
    Optional<FlexibleDayTaskPlan> findByTaskId(Long taskId);
}
