package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

class KnowledgeRecordDeletionServiceTest {
    private static final Instant NOW = Instant.parse("2030-08-10T04:00:00Z");
    private final ObjectAnnotationRepository repository = mock(ObjectAnnotationRepository.class);
    private final ConversationContextService context = mock(ConversationContextService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final KnowledgeRecordDeletionService service = new KnowledgeRecordDeletionService(
            repository, context, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void ordinalDeleteOnlyPreviewsAndStoresPendingTarget() {
        ObjectAnnotation annotation = annotation();
        when(context.objectAnnotationIdAt(2)).thenReturn(81L);
        when(repository.findById(81L)).thenReturn(Optional.of(annotation));
        Runnable mutation = mock(Runnable.class);

        var result = service.answer("刪除第二筆", mutation).orElseThrow();

        assertThat(result.message()).contains("準備刪除第 2 筆", "確認刪除", "取消刪除");
        verify(context).prepareObjectAnnotationDelete(81L);
        verify(mutation, never()).run();
        assertThat(annotation.isArchived()).isFalse();
    }

    @Test
    void confirmationArchivesPendingTargetAndPublishesLifeEvent() {
        ObjectAnnotation annotation = annotation();
        when(context.pendingObjectAnnotationDeleteId()).thenReturn(81L);
        when(repository.findById(81L)).thenReturn(Optional.of(annotation));
        Runnable mutation = mock(Runnable.class);

        var result = service.answer("確認刪除", mutation).orElseThrow();

        assertThat(result.message()).contains("已刪除", "可恢復封存");
        assertThat(annotation.getArchivedAt()).isEqualTo(NOW);
        verify(mutation).run();
        verify(context).clearObjectAnnotationDelete();
        verify(events).publishEvent(new ObjectAnnotationArchivedEvent(81L, "家裡油漆色號", NOW));
    }

    @Test
    void missingDisplayedOrdinalDoesNotMutateAnything() {
        when(context.objectAnnotationIdAt(1)).thenReturn(0L);
        when(repository.findById(0L)).thenReturn(Optional.empty());
        Runnable mutation = mock(Runnable.class);

        var result = service.answer("刪除第一筆", mutation).orElseThrow();

        assertThat(result.message()).contains("請先重新查詢");
        verify(mutation, never()).run();
        verify(context, never()).prepareObjectAnnotationDelete(anyLong());
    }

    private static ObjectAnnotation annotation() {
        return ObjectAnnotation.create(ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "家裡油漆色號", "大麥白 107", Instant.parse("2026-07-20T00:17:10Z"));
    }
}
