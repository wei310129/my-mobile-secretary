package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationExitRecorded;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationArchivedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationUpdatedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderTriggeredEvent;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCanceledEvent;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCompletedEvent;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionEvent;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Converts existing product domain events into tagged life records without exposing raw GPS. */
@Component
public class UniversalDomainEventRecorder {
    private final UniversalLifeRecordService lifeRecordService;
    private final Clock clock;

    public UniversalDomainEventRecorder(UniversalLifeRecordService lifeRecordService, Clock clock) {
        this.lifeRecordService = lifeRecordService;
        this.clock = clock;
    }

    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.TASK,
                event.title(), Instant.now(clock), List.of("待辦", "建立"));
    }

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.TASK,
                event.title() == null ? "完成待辦" : event.title(),
                event.completedAt(), List.of("待辦", "完成"));
    }

    @EventListener
    public void onTaskCanceled(TaskCanceledEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.TASK,
                event.title(), event.canceledAt(), List.of("待辦", "取消"));
    }

    @EventListener
    public void onScheduleLifecycle(ScheduleLifecycleEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.SCHEDULE,
                event.title(), event.occurredAt(),
                List.of("行程", scheduleAction(event.action())));
    }

    @EventListener
    public void onReminderTriggered(ReminderTriggeredEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.REMINDER,
                event.taskTitle(), event.triggeredAt(), List.of("提醒", "觸發"));
    }

    @EventListener
    public void onPlaceCreated(PlaceCreatedEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.PLACE,
                event.name(), event.createdAt(), List.of("地點", "建立"));
    }

    @EventListener
    public void onItemLifecycle(ItemLifecycleEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                event.itemName(), event.occurredAt(),
                List.of("物品", itemAction(event.action())));
    }

    @EventListener
    public void onObjectAnnotationArchived(ObjectAnnotationArchivedEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                event.subject(), event.archivedAt(), List.of("知識紀錄", "刪除"));
    }

    @EventListener
    public void onObjectAnnotationUpdated(ObjectAnnotationUpdatedEvent event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                event.subject(), event.updatedAt(), List.of("知識紀錄", "編修"));
    }

    @EventListener
    public void onLocationExit(LocationExitRecorded event) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.PLACE,
                "離開位置區域", event.occurredAt(), List.of("位置事件", "離開"));
    }

    @EventListener
    public void onScheduleOutcome(ScheduleOutcomeRecorded event) {
        String outcome = event.onTime() ? "準時" : "超時";
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.SCHEDULE,
                "行程結果：" + outcome, Instant.now(clock), List.of("行程", "結果", outcome));
    }

    @EventListener
    public void onWorkSchoolSuspension(WorkSchoolSuspensionEvent event) {
        String action = switch (event.action()) {
            case CAPTURED -> "圖片抄錄";
            case DECLINED -> "略過官方查核";
            case OFFICIAL_CONFIRMED -> "官方已證實";
            case OFFICIAL_NOT_CONFIRMED -> "官方未能證實";
            case VERIFICATION_FAILED -> "官方查核失敗";
        };
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "停班停課資訊 " + event.noticeDate(), event.occurredAt(),
                List.of("停班停課", "天然災害", action));
    }

    private static String scheduleAction(ScheduleLifecycleEvent.Action action) {
        return switch (action) {
            case CREATED -> "建立";
            case REJECTED -> "放棄";
            case CANCELED -> "取消";
            case COMPLETED -> "完成";
        };
    }

    private static String itemAction(ItemLifecycleEvent.Action action) {
        return switch (action) {
            case CREATED -> "建立";
            case SHOPPING_ADDED -> "加入購物清單";
            case SHOPPING_REMOVED -> "移出購物清單";
            case PURCHASED -> "購買";
            case INVENTORY_ADJUSTED -> "調整庫存";
            case INVENTORY_SET -> "設定庫存";
            case RESTOCK_REQUESTED -> "補貨";
            case SHOPPING_CLEARED -> "清空購物清單";
            case PLACE_BOUND -> "綁定地點";
        };
    }
}
