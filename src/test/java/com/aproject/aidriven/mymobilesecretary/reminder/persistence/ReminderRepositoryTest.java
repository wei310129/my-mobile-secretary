package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reminder 的 JPA mapping 測試(Phase 1A 尚無 API,直接驗證存取層)。
 */
class ReminderRepositoryTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-09T08:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Test
    void savesAndReloadsReminderWithAllFields() {
        // reminder 有 FK 指向 task,先建任務
        Task task = taskRepository.save(Task.create("買蛤蜊", null, TaskPriority.HIGH, null, NOW));

        Reminder saved = reminderRepository.save(
                Reminder.triggered(task.getId(), "ENTER geofence: 菜市場", NOW));

        List<Reminder> found = reminderRepository.findByTaskId(task.getId());
        assertThat(found).hasSize(1);
        Reminder reloaded = found.get(0);
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReminderStatus.TRIGGERED);
        assertThat(reloaded.getTriggeredAt()).isEqualTo(NOW);
        assertThat(reloaded.getTriggerReason()).isEqualTo("ENTER geofence: 菜市場");
        assertThat(reloaded.getConfirmedAt()).isNull();
    }
}
