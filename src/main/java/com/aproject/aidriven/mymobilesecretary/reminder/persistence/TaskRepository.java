package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task 資料存取。 */
public interface TaskRepository extends JpaRepository<Task, Long> {
}
