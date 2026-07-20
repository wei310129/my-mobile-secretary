package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class KnowledgeRecordEditingServiceTest {
    @Test
    void editsNumberedKnowledgeInPlaceAndPreservesCreationTime() {
        Instant created = Instant.parse("2026-07-20T00:00:00Z");
        Instant updated = Instant.parse("2026-07-21T00:00:00Z");
        ObjectAnnotation annotation = ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "油漆色號", "舊內容", created);
        ObjectAnnotationRepository repository = mock(ObjectAnnotationRepository.class);
        ConversationContextService context = mock(ConversationContextService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(context.objectAnnotationIdAt(2)).thenReturn(81L);
        when(repository.findById(81L)).thenReturn(Optional.of(annotation));
        Runnable mutation = mock(Runnable.class);
        KnowledgeRecordEditingService service = new KnowledgeRecordEditingService(
                repository, context, events, Clock.fixed(updated, ZoneOffset.UTC));

        var result = service.answer(
                "把第2筆知識紀錄的內容改成：客廳牆面使用大麥白107", mutation).orElseThrow();

        assertThat(result.message()).contains("已更新第 2 筆", "最後修改時間已更新");
        assertThat(annotation.getDetail()).isEqualTo("客廳牆面使用大麥白107");
        assertThat(annotation.getCreatedAt()).isEqualTo(created);
        assertThat(annotation.getUpdatedAt()).isEqualTo(updated);
        verify(mutation).run();
        verify(events).publishEvent(new ObjectAnnotationUpdatedEvent(81L, "油漆色號", updated));
    }

    @Test
    void canRenameKnowledgeWithoutChangingDetail() {
        Instant created = Instant.parse("2026-07-20T00:00:00Z");
        ObjectAnnotation annotation = ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "舊標題", "既有內容", created);
        ObjectAnnotationRepository repository = mock(ObjectAnnotationRepository.class);
        ConversationContextService context = mock(ConversationContextService.class);
        when(context.objectAnnotationIdAt(1)).thenReturn(82L);
        when(repository.findById(82L)).thenReturn(Optional.of(annotation));
        KnowledgeRecordEditingService service = new KnowledgeRecordEditingService(
                repository, context, mock(ApplicationEventPublisher.class),
                Clock.fixed(created.plusSeconds(60), ZoneOffset.UTC));

        service.answer("把第1筆知識紀錄的標題改成：客廳油漆色號", () -> {}).orElseThrow();

        assertThat(annotation.getSubject()).isEqualTo("客廳油漆色號");
        assertThat(annotation.getDetail()).isEqualTo("既有內容");
    }
}
