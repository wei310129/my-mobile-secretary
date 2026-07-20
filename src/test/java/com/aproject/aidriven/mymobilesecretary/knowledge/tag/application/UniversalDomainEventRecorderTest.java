package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemLifecycleEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationArchivedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationUpdatedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCreatedEvent;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleLifecycleEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
