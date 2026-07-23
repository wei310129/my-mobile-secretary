package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task 資料存取。 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByCreatedByUserId(UUID actorId);

    Optional<Task> findByIdAndCreatedByUserId(Long id, UUID actorId);

    List<Task> findByStatusInAndCreatedByUserId(
            Collection<TaskStatus> statuses, UUID actorId);
}
