package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task 資料存取。 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByStatusIn(Collection<TaskStatus> statuses);
}
