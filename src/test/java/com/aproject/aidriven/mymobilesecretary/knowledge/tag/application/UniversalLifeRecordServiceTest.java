package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ConsumptionTagCatalog;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class UniversalLifeRecordServiceTest {
    private static final Instant NOW = Instant.parse("2030-08-10T04:00:00Z");

    @Test
    void everyNonFeedbackUtteranceCreatesTaggedLifeRecord() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        UniversalLifeRecordService service = service(graph);

        service.recordUtterance("我在全國電子買了冰箱，也提到節能補助",
                IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "收到"));

        verify(graph).recordLifeEvent(eq(TaggedLifeRecord.RecordType.USER_UTTERANCE),
                eq("我在全國電子買了冰箱，也提到節能補助"), eq(NOW), eq(null), anyList());
    }

    @Test
    void developmentFeedbackIsExcluded() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);

        service(graph).recordUtterance("請新增所有東西都要有標籤",
                IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED, "收到"));

        verify(graph, never()).recordLifeEvent(any(), any(), any(), any(), anyList());
    }

    @Test
    void sensitiveMedicalTextUsesSafeRecordTitle() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);

        service(graph).recordUtterance("身分證 A123456789，診斷是蛀牙",
                IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "收到"));

        verify(graph).recordLifeEvent(eq(TaggedLifeRecord.RecordType.USER_UTTERANCE),
                eq("醫療相關使用者輸入"), eq(NOW), eq(null), anyList());
    }

    @Test
    void sensitiveDomainEventAlsoUsesSafeRecordTitle() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);

        service(graph).recordDomainEvent(TaggedLifeRecord.RecordType.TASK,
                "病歷診斷回診 A123456789", NOW, List.of("待辦", "建立"));

        verify(graph).recordLifeEvent(eq(TaggedLifeRecord.RecordType.TASK),
                eq("醫療相關使用者輸入"), eq(NOW), eq(null), anyList());
    }

    private static UniversalLifeRecordService service(SemanticTagGraphService graph) {
        return new UniversalLifeRecordService(graph, new ConsumptionTagCatalog(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
