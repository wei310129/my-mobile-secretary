package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarAdoptionCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeCanceledEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeLocationRevisedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeRevisedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationArchivedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationUpdatedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleLifecycleEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UniversalDomainEventRecorderTest {
    @Test
    void taskCreationBecomesTaggedLifeEvent() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));

        recorder.onTaskCreated(new TaskCreatedEvent(7L, "申請節能補助"));

        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.TASK,
                "申請節能補助", now, List.of("待辦", "建立"));
    }

    @Test
    void everyProjectLifecycleMutationBecomesTaggedLifeEvent() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));
        java.util.UUID projectId = java.util.UUID.randomUUID();

        recorder.onProjectLifecycle(new ProjectLifecycleEvent(
                projectId, ProjectType.TRAVEL, "大阪旅行",
                ProjectLifecycleEvent.Action.CREATED, now));
        recorder.onProjectLifecycle(new ProjectLifecycleEvent(
                projectId, ProjectType.TRAVEL, "大阪與京都",
                ProjectLifecycleEvent.Action.RENAMED, now));
        recorder.onProjectLifecycle(new ProjectLifecycleEvent(
                projectId, ProjectType.TRAVEL, "大阪與京都",
                ProjectLifecycleEvent.Action.COMPLETED, now));
        recorder.onProjectLifecycle(new ProjectLifecycleEvent(
                projectId, ProjectType.TRAVEL, "大阪與京都",
                ProjectLifecycleEvent.Action.REOPENED, now));
        recorder.onProjectLifecycle(new ProjectLifecycleEvent(
                projectId, ProjectType.TRAVEL, "大阪與京都",
                ProjectLifecycleEvent.Action.ARCHIVED, now));

        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.PROJECT,
                "大阪旅行", now, List.of("專案", "建立"));
        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.PROJECT,
                "大阪與京都", now, List.of("專案", "改名"));
        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.PROJECT,
                "大阪與京都", now, List.of("專案", "完成"));
        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.PROJECT,
                "大阪與京都", now, List.of("專案", "重新開啟"));
        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.PROJECT,
                "大阪與京都", now, List.of("專案", "封存"));
    }

    @Test
    void calendarEventsUseStableDomainIdentityForExactlyOnceRecording() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));
        UUID planId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID reminderId = UUID.randomUUID();
        UUID adoptionId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        recorder.onCalendarPlanCreated(
                new CalendarPlanCreatedEvent(planId, "北海道旅行", now));
        recorder.onCalendarPlanLifecycle(new CalendarPlanLifecycleEvent(
                planId,
                "北海道旅行",
                CalendarPlanLifecycleEvent.Action.CANCELED,
                now));
        recorder.onCalendarNodeCreated(
                new CalendarNodeCreatedEvent(nodeId, "抵達", now, now));
        recorder.onCalendarNodeRevised(
                new CalendarNodeRevisedEvent(nodeId, "抵達", 1, 2, now, now));
        recorder.onCalendarNodeLocationRevised(
                new CalendarNodeLocationRevisedEvent(nodeId, "抵達", 3, now));
        recorder.onCalendarNodeCanceled(
                new CalendarNodeCanceledEvent(nodeId, "抵達", 4, now));
        recorder.onCalendarReminderLifecycle(new CalendarReminderLifecycleEvent(
                reminderId,
                CalendarReminderLifecycleEvent.Action.CREATED,
                "抵達",
                now));
        recorder.onCalendarAdoptionCreated(
                new CalendarAdoptionCreatedEvent(adoptionId, "北海道旅行", 1, now));
        recorder.onCalendarOwnershipTransfer(
                new CalendarOwnershipTransferLifecycleEvent(
                        transferId,
                        planId,
                        CalendarOwnershipTransferLifecycleEvent.Action.ACCEPTED,
                        now));

        verify(service).recordDomainEventOnce(
                "calendar-plan/" + planId + "/created",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "北海道旅行",
                now,
                List.of("行程計畫", "建立"));
        verify(service).recordDomainEventOnce(
                "calendar-plan/" + planId + "/canceled",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "北海道旅行",
                now,
                List.of("行程計畫", "取消"));
        verify(service).recordDomainEventOnce(
                "calendar-ownership-transfer/" + transferId + "/accepted",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "日曆所有權移轉",
                now,
                List.of("行程計畫", "所有權移轉", "接受"));
        verify(service).recordDomainEventOnce(
                "calendar-node/" + nodeId + "/created",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "抵達",
                now,
                List.of("行程節點", "建立"));
        verify(service).recordDomainEventOnce(
                "calendar-node/" + nodeId + "/time/2",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "抵達",
                now,
                List.of("行程節點", "修訂"));
        verify(service).recordDomainEventOnce(
                "calendar-node/" + nodeId + "/location/3",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "抵達",
                now,
                List.of("行程節點", "地點修訂"));
        verify(service).recordDomainEventOnce(
                "calendar-node/" + nodeId + "/canceled/4",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "抵達",
                now,
                List.of("行程節點", "取消"));
        verify(service).recordDomainEventOnce(
                "calendar-reminder/" + reminderId + "/created",
                TaggedLifeRecord.RecordType.REMINDER,
                "抵達",
                now,
                List.of("日曆提醒", "建立"));
        verify(service).recordDomainEventOnce(
                "calendar-adoption/" + adoptionId + "/created",
                TaggedLifeRecord.RecordType.SCHEDULE,
                "北海道旅行",
                now,
                List.of("行程", "採用"));
    }

    @Test
    void scheduleAndShoppingChangesBecomeTaggedLifeEvents() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));

        recorder.onScheduleLifecycle(new ScheduleLifecycleEvent(
                8L, "牙醫回診", ScheduleLifecycleEvent.Action.COMPLETED, now));
        recorder.onItemLifecycle(new ItemLifecycleEvent(
                9L, "牛奶", ItemLifecycleEvent.Action.SHOPPING_ADDED, null, now));

        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.SCHEDULE,
                "牙醫回診", now, List.of("行程", "完成"));
        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "牛奶", now, List.of("物品", "加入購物清單"));
    }

    @Test
    void knowledgeDeletionBecomesTaggedLifeEvent() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));

        recorder.onObjectAnnotationArchived(new ObjectAnnotationArchivedEvent(
                12L, "家裡油漆色號", now));

        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "家裡油漆色號", now, List.of("知識紀錄", "刪除"));
    }

    @Test
    void knowledgeEditBecomesTaggedLifeEvent() {
        UniversalLifeRecordService service = mock(UniversalLifeRecordService.class);
        Instant now = Instant.parse("2030-08-10T04:00:00Z");
        UniversalDomainEventRecorder recorder = new UniversalDomainEventRecorder(
                service, Clock.fixed(now, ZoneOffset.UTC));

        recorder.onObjectAnnotationUpdated(new ObjectAnnotationUpdatedEvent(
                12L, "家裡油漆色號", now));

        verify(service).recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "家裡油漆色號", now, List.of("知識紀錄", "編修"));
    }
}
