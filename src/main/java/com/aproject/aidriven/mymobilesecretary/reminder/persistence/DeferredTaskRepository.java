package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeferredTaskRepository extends JpaRepository<DeferredTask, Long> {

    List<DeferredTask> findAllByPredecessorTaskIdAndStatusOrderByIdAsc(
            Long predecessorTaskId, DeferredTask.Status status);

    Optional<DeferredTask> findFirstByPredecessorTaskIdAndTitleIgnoreCaseAndStatus(
            Long predecessorTaskId, String title, DeferredTask.Status status);
}
