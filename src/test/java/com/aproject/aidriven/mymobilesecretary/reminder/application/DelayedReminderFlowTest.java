package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.ScheduledEntry;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 1D 驗收流程:Redis 延遲佇列 → 到期觸發 → 升級催促鏈,走真實 Redis 與 PostgreSQL。
 *
 * 測試環境已關閉背景輪詢(app.scheduling.enabled=false),
 * 由測試直接呼叫 worker.process(now) 控制時序。
 */
class DelayedReminderFlowTest extends IntegrationTestBase {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ReminderScheduleService scheduleService;

    @Autowired
    private ReminderQueueWorker worker;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    /** 佇列基本行為:到期項目取得 lease；成功 ack 才永久移除。 */
    @Test
    void claimReturnsOnlyDueEntriesAndRemovesThem() {
        Instant now = Instant.now();
        // 用不存在的大 task id,避免影響其他測試
        scheduleService.scheduleDueReminder(900_001L, now.minusSeconds(60));
        scheduleService.scheduleDueReminder(900_002L, now.plus(Duration.ofHours(1)));

        List<ScheduledEntry> claimed = scheduleService.claimDue(now);

        assertThat(claimed).contains(new ScheduledEntry(ScheduledEntry.Kind.DUE, 900_001L, 0));
        assertThat(claimed).doesNotContain(new ScheduledEntry(ScheduledEntry.Kind.DUE, 900_002L, 0));
        // 已被 claim 的不會再出現;未到期的還在
        assertThat(scheduleService.peekDue(900_001L)).isEmpty();
        assertThat(scheduleService.peekDue(900_002L)).isPresent();
        ScheduledEntry leased = claimed.stream()
                .filter(entry -> entry.id() == 900_001L)
                .findFirst().orElseThrow();
        assertThat(scheduleService.acknowledge(leased)).isTrue();
        scheduleService.removeDueReminder(900_002L);
    }

    /** 處理失敗會回 ready 重試，成功後才 ack 並清除 failure count。 */
    @Test
    void failedLeaseCanRetryAndThenAcknowledge() {
        Instant now = Instant.now();
        scheduleService.scheduleDueReminder(900_003L, now.minusSeconds(1));
        ScheduledEntry firstLease = scheduleService.claimDue(now).stream()
                .filter(entry -> entry.id() == 900_003L)
                .findFirst().orElseThrow();

        assertThat(scheduleService.retry(firstLease, now))
                .isEqualTo(ReminderScheduleService.RetryOutcome.RETRIED);
        assertThat(scheduleService.failureCount(firstLease)).isEqualTo(1);

        ScheduledEntry secondLease = scheduleService.claimDue(now.plusSeconds(31)).stream()
                .filter(entry -> entry.id() == 900_003L)
                .findFirst().orElseThrow();
        assertThat(scheduleService.acknowledge(secondLease)).isTrue();
        assertThat(scheduleService.failureCount(secondLease)).isZero();
    }

    /** 驗收 1+2:建立未來時間提醒,到期後由 worker 觸發。 */
    @Test
    void dueTaskFiresWhenProcessed() {
        // dueAt 已過 → 立刻可觸發
        Task task = taskService.createTask("繳學費", null, TaskPriority.NORMAL,
                Instant.now().minusSeconds(1));
        assertThat(scheduleService.peekDue(task.getId())).isPresent();

        worker.process(Instant.now());

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.REMINDED);
        List<Reminder> reminders = reminderRepository.findByTaskId(task.getId());
        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).getTriggerReason()).isEqualTo("任務到期");
        // 觸發後自動排入第一次升級催促
        assertThat(scheduleService.peekEscalation(reminders.get(0).getId(), 1)).isPresent();
    }

    /** 驗收:未到期的提醒不得送出。 */
    @Test
    void futureDueTaskDoesNotFire() {
        Task task = taskService.createTask("未來任務", null, TaskPriority.NORMAL,
                Instant.now().plus(Duration.ofHours(1)));

        worker.process(Instant.now());

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(reminderRepository.findByTaskId(task.getId())).isEmpty();
        assertThat(scheduleService.peekDue(task.getId())).isPresent();
    }

    /** 驗收 3:未確認 → 升級催促,並排下一輪;催到上限即停。 */
    @Test
    void unconfirmedReminderEscalatesUntilMax() {
        Task task = taskService.createTask("接小孩", null, TaskPriority.HIGH,
                Instant.now().minusSeconds(1));
        worker.process(Instant.now());
        long reminderId = reminderRepository.findByTaskId(task.getId()).get(0).getId();

        // 把第 1 次催促改排到過去 → 立刻到期
        scheduleService.scheduleEscalation(reminderId, 1, Instant.now().minusSeconds(1));
        worker.process(Instant.now());

        Reminder afterFirst = reminderRepository.findById(reminderId).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(ReminderStatus.ESCALATED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.ESCALATED);
        // 第 2 輪已排定
        assertThat(scheduleService.peekEscalation(reminderId, 2)).isPresent();

        // 直接模擬最後一次(第 3 次)催促 → 不得再排第 4 輪
        scheduleService.scheduleEscalation(reminderId, 3, Instant.now().minusSeconds(1));
        worker.process(Instant.now());
        assertThat(scheduleService.peekEscalation(reminderId, 4)).isEmpty();
    }

    /** 已確認的提醒:催促鏈立即終止。 */
    @Test
    void confirmedReminderStopsEscalationChain() {
        Task task = taskService.createTask("已處理的事", null, TaskPriority.NORMAL,
                Instant.now().minusSeconds(1));
        worker.process(Instant.now());
        Reminder reminder = reminderRepository.findByTaskId(task.getId()).get(0);

        reminder.confirm(Instant.now());
        reminderRepository.save(reminder);

        scheduleService.scheduleEscalation(reminder.getId(), 1, Instant.now().minusSeconds(1));
        worker.process(Instant.now());

        Reminder reloaded = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReminderStatus.CONFIRMED);
        // 沒有下一輪
        assertThat(scheduleService.peekEscalation(reminder.getId(), 2)).isEmpty();
    }
}
