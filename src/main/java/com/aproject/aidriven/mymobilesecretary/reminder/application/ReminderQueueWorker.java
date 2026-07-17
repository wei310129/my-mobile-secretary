package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.RecoveryResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.RetryOutcome;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.ScheduledEntry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls every workspace's reliable delayed reminder queue. */
@Component
public class ReminderQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(ReminderQueueWorker.class);

    private final ReminderScheduleService scheduleService;
    private final ReminderTriggerService triggerService;
    private final ReminderEscalationService escalationService;
    private final ReminderConditionService conditionService;
    private final TaskService taskService;
    private final Clock clock;
    private final WorkspaceBackgroundRunner workspaceRunner;

    public ReminderQueueWorker(ReminderScheduleService scheduleService,
                               ReminderTriggerService triggerService,
                               ReminderEscalationService escalationService,
                               ReminderConditionService conditionService,
                               TaskService taskService,
                               Clock clock,
                               WorkspaceBackgroundRunner workspaceRunner) {
        this.scheduleService = scheduleService;
        this.triggerService = triggerService;
        this.escalationService = escalationService;
        this.conditionService = conditionService;
        this.taskService = taskService;
        this.clock = clock;
        this.workspaceRunner = workspaceRunner;
    }

    @Scheduled(fixedDelayString = "${app.reminder.poll-interval:5s}")
    public void poll() {
        Instant now = Instant.now(clock);
        workspaceRunner.forEachWorkspace("reminder-queue", ignored -> process(now));
    }

    /** Per workspace: recover expired leases, claim due work, then ack or retry every entry. */
    public void process(Instant now) {
        RecoveryResult recovery = scheduleService.recoverExpired(now);
        if (recovery.total() > 0) {
            log.warn("Recovered reminder queue leases [retried={}, deadLettered={}]",
                    recovery.retried(), recovery.deadLettered());
        }
        for (ScheduledEntry entry : scheduleService.claimDue(now)) {
            try {
                boolean requiresAck = processEntry(entry, now);
                if (requiresAck && !scheduleService.acknowledge(entry)) {
                    log.warn("Reminder queue ack ignored for stale lease [entry={}]", entry);
                }
            } catch (Exception exception) {
                RetryOutcome outcome = scheduleService.retry(entry, now);
                if (outcome == RetryOutcome.DEAD_LETTERED) {
                    log.error("Reminder queue entry moved to dead letter [entry={}]", entry, exception);
                } else {
                    log.error("Reminder queue processing failed [entry={}, outcome={}]",
                            entry, outcome, exception);
                }
            }
        }
    }

    private boolean processEntry(ScheduledEntry entry, Instant now) {
        return switch (entry.kind()) {
            case DUE -> processDue(entry.id(), now);
            case ESCALATION -> {
                escalationService.escalate(entry.id(), entry.attempt());
                yield true;
            }
        };
    }

    private boolean processDue(long taskId, Instant now) {
        return switch (conditionService.evaluate(taskId)) {
            case TRIGGER -> {
                triggerService.tryTrigger(taskId, "任務到期")
                        .ifPresent(ignored -> taskService.scheduleNextRecurringOccurrence(taskId));
                yield true;
            }
            case SKIP -> {
                taskService.skipConditionalTask(taskId);
                yield true;
            }
            case RETRY -> {
                // enqueue atomically replaces the current lease, so no separate ack is needed.
                scheduleService.scheduleDueReminder(taskId, now.plusSeconds(15 * 60));
                yield false;
            }
        };
    }
}
