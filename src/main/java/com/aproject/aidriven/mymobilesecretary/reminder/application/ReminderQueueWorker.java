package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderScheduleService.ScheduledEntry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 延遲提醒佇列的輪詢處理者:定期撈出到期項目,分派給觸發/升級服務。
 *
 * 測試環境關閉背景輪詢(app.scheduling.enabled=false),由測試直接呼叫 process()。
 */
@Component
public class ReminderQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(ReminderQueueWorker.class);

    private final ReminderScheduleService scheduleService;
    private final ReminderTriggerService triggerService;
    private final ReminderEscalationService escalationService;
    private final ReminderConditionService conditionService;
    private final TaskService taskService;
    private final Clock clock;

    public ReminderQueueWorker(ReminderScheduleService scheduleService,
                               ReminderTriggerService triggerService,
                               ReminderEscalationService escalationService,
                               ReminderConditionService conditionService,
                               TaskService taskService,
                               Clock clock) {
        this.scheduleService = scheduleService;
        this.triggerService = triggerService;
        this.escalationService = escalationService;
        this.conditionService = conditionService;
        this.taskService = taskService;
        this.clock = clock;
    }

    /** 背景輪詢進入點。 */
    @Scheduled(fixedDelayString = "${app.reminder.poll-interval:5s}")
    public void poll() {
        process(Instant.now(clock));
    }

    /**
     * 處理所有到期的排程項目。
     *
     * 關鍵規則:單一項目失敗只記錄,不能拖垮同批其他項目——
     * 排程項目已從佇列移除,失敗就丟(提醒可靠度靠 DB 紀錄事後追查)。
     */
    public void process(Instant now) {
        for (ScheduledEntry entry : scheduleService.claimDue(now)) {
            try {
                switch (entry.kind()) {
                    // 任務到期:交給觸發服務(狀態與 debounce 守門在裡面)
                    case DUE -> processDue(entry.id(), now);
                    // 升級催促:交給升級服務(確認狀態檢查在裡面)
                    case ESCALATION -> escalationService.escalate(entry.id(), entry.attempt());
                }
            } catch (Exception e) {
                log.error("Failed to process schedule entry [{}]", entry, e);
            }
        }
    }

    private void processDue(long taskId, Instant now) {
        switch (conditionService.evaluate(taskId)) {
            case TRIGGER -> triggerService.tryTrigger(taskId, "任務到期")
                    .ifPresent(ignored -> taskService.scheduleNextRecurringOccurrence(taskId));
            case SKIP -> taskService.skipConditionalTask(taskId);
            case RETRY -> scheduleService.scheduleDueReminder(taskId, now.plusSeconds(15 * 60));
        }
    }
}
