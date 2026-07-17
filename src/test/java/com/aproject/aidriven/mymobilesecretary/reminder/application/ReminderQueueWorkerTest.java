package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.RecoveryResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.RetryOutcome;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.ScheduledEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderQueueWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock private ReminderScheduleService scheduleService;
    @Mock private ReminderTriggerService triggerService;
    @Mock private ReminderEscalationService escalationService;
    @Mock private ReminderConditionService conditionService;
    @Mock private TaskService taskService;
    @Mock private WorkspaceBackgroundRunner workspaceRunner;

    private ReminderQueueWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ReminderQueueWorker(scheduleService, triggerService, escalationService,
                conditionService, taskService, Clock.fixed(NOW, ZoneOffset.UTC), workspaceRunner);
        when(scheduleService.recoverExpired(NOW)).thenReturn(RecoveryResult.NONE);
    }

    @Test
    void successfulEntryIsAcknowledged() {
        ScheduledEntry entry = new ScheduledEntry(ScheduledEntry.Kind.DUE, 7L, 0);
        when(scheduleService.claimDue(NOW)).thenReturn(List.of(entry));
        when(conditionService.evaluate(7L)).thenReturn(ReminderConditionService.Decision.SKIP);

        worker.process(NOW);

        verify(taskService).skipConditionalTask(7L);
        verify(scheduleService).acknowledge(entry);
        verify(scheduleService, never()).retry(entry, NOW);
    }

    @Test
    void processingFailureRetriesInsteadOfLosingEntry() {
        ScheduledEntry entry = new ScheduledEntry(ScheduledEntry.Kind.ESCALATION, 8L, 2);
        when(scheduleService.claimDue(NOW)).thenReturn(List.of(entry));
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary"))
                .when(escalationService).escalate(8L, 2);
        when(scheduleService.retry(entry, NOW)).thenReturn(RetryOutcome.RETRIED);

        worker.process(NOW);

        verify(scheduleService).retry(entry, NOW);
        verify(scheduleService, never()).acknowledge(entry);
    }

    @Test
    void conditionRetryAtomicallyReschedulesWithoutStaleAck() {
        ScheduledEntry entry = new ScheduledEntry(ScheduledEntry.Kind.DUE, 9L, 0);
        when(scheduleService.claimDue(NOW)).thenReturn(List.of(entry));
        when(conditionService.evaluate(9L)).thenReturn(ReminderConditionService.Decision.RETRY);

        worker.process(NOW);

        verify(scheduleService).scheduleDueReminder(9L, NOW.plusSeconds(15 * 60));
        verify(scheduleService, never()).acknowledge(entry);
        verify(scheduleService, never()).retry(entry, NOW);
    }
}
