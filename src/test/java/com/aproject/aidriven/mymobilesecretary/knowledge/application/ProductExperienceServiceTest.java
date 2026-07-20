package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ProductObservationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ProductExperienceServiceTest {
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void captureAddsPublicTaxonomyButRejectsPrivateClaimsFromImageSuggestions() {
        ProductObservationDraftRepository drafts = mock(ProductObservationDraftRepository.class);
        ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
        UserKnowledgeService knowledge = mock(UserKnowledgeService.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        ProductObservationDraft saved = ProductObservationDraft.create(
                "水泥漆", "青葉", "大麥白", "商品圖", NOW, NOW.plusSeconds(600));
        when(drafts.save(any())).thenReturn(saved);
        ProductExperienceService service = new ProductExperienceService(
                drafts, annotations, knowledge, graph, Clock.fixed(NOW, ZoneOffset.UTC));

        service.capture(new com.aproject.aidriven.mymobilesecretary.intent.application
                .ReceiptCommand.PaintProductInfo("水泥漆", "青葉", "大麥白", "107",
                List.of("建築塗裝", "客廳使用", "朋友推薦", "過敏提醒")), "商品圖");

        verify(graph).relate("水泥漆", SemanticTag.Kind.PRODUCT,
                com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge
                        .RelationType.RELATED_TO,
                "居家修繕", SemanticTag.Kind.TOPIC,
                com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge
                        .SourceType.SYSTEM_RULE);
        verify(graph).relate("水泥漆", SemanticTag.Kind.PRODUCT,
                com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge
                        .RelationType.RELATED_TO,
                "建築塗裝", SemanticTag.Kind.TOPIC,
                com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge
                        .SourceType.IMPORT);
        verify(graph, org.mockito.Mockito.never()).relate(any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("客廳"), any(), any());
        verify(graph, atLeastOnce()).relate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cautionUsesGenericAnnotationAndPurchaseReminderTagInsteadOfCategoryColumn() {
        ProductObservationDraftRepository drafts = mock(ProductObservationDraftRepository.class);
        ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
        UserKnowledgeService knowledge = mock(UserKnowledgeService.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "得利", "百合白", "油漆標籤", NOW, NOW.plusSeconds(600));
        ReflectionTestUtils.setField(draft, "id", 9L);
        ObjectAnnotation annotation = mock(ObjectAnnotation.class);
        when(annotation.getId()).thenReturn(12L);
        when(annotations.save(any())).thenReturn(annotation);
        when(drafts.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                ACTOR, ProductObservationDraft.Status.PENDING_PURPOSE, NOW))
                .thenReturn(Optional.of(draft));
        ProductExperienceService service = new ProductExperienceService(
                drafts, annotations, knowledge, graph, Clock.fixed(NOW, ZoneOffset.UTC));

        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            service.recordLatest(ProductExperienceService.ExperienceKind.CAUTION,
                    "這個油漆害我過敏，以後買油漆提醒我");
        }

        assertThat(draft.getStatus()).isEqualTo(ProductObservationDraft.Status.RESOLVED);
        verify(knowledge, never()).remember(any(), any(), any());
        verify(graph).indexObjectAnnotation(org.mockito.ArgumentMatchers.eq(12L), any());
        verify(graph).recordLifeEvent(any(), any(), any(), any(), any());
    }

    @Test
    void genericPaintShoppingMatchesExplicitPaintCaution() {
        ProductObservationDraftRepository drafts = mock(ProductObservationDraftRepository.class);
        ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
        UserKnowledgeService knowledge = mock(UserKnowledgeService.class);
        UserKnowledgeFact fact = mock(UserKnowledgeFact.class);
        when(fact.getNormalizedSubject()).thenReturn("得利水泥漆百合白");
        when(fact.getSubject()).thenReturn("得利 水泥漆 百合白");
        when(fact.getDetail()).thenReturn("曾明確回報不適");
        when(knowledge.list(Category.PRODUCT_CAUTION)).thenReturn(List.of(fact));
        ProductExperienceService service = new ProductExperienceService(
                drafts, annotations, knowledge, mock(SemanticTagGraphService.class),
                Clock.systemUTC());

        assertThat(service.purchaseCautions(List.of("油漆")))
                .extracting(ProductExperienceService.PurchaseCaution::product)
                .containsExactly("得利 水泥漆 百合白");
    }

    @Test
    void resolvedRecentProductCanReceiveAdditionalGenericAnnotations() {
        ProductObservationDraftRepository drafts = mock(ProductObservationDraftRepository.class);
        ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
        UserKnowledgeService knowledge = mock(UserKnowledgeService.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "青葉水泥漆", "百合白", "商品圖", NOW, NOW.plusSeconds(600));
        ReflectionTestUtils.setField(draft, "id", 21L);
        draft.resolve(NOW);
        ObjectAnnotation annotation = mock(ObjectAnnotation.class);
        when(annotation.getId()).thenReturn(22L);
        when(annotations.save(any())).thenReturn(annotation);
        when(drafts.findFirstByCreatedByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(ACTOR, NOW))
                .thenReturn(Optional.of(draft));
        ProductExperienceService service = new ProductExperienceService(
                drafts, annotations, knowledge, graph, Clock.fixed(NOW, ZoneOffset.UTC));

        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            service.recordLatestAnnotation(
                    List.of("第二批色差"), "第二批施工有色差", false);
        }

        verify(graph).indexObjectAnnotation(org.mockito.ArgumentMatchers.eq(22L), any());
        ArgumentCaptor<List<SemanticTagGraphService.TagSpec>> tags = ArgumentCaptor.forClass(List.class);
        verify(graph).recordLifeEvent(any(), any(), any(), any(), tags.capture());
        assertThat(tags.getValue()).extracting(SemanticTagGraphService.TagSpec::name)
                .contains("水泥漆", "青葉水泥漆", "油漆", "青葉油漆", "漆");
        verify(drafts, never()).save(draft);
    }

    @Test
    void completingDraftPersistsOnlyObservedProductFactsAsKnowledge() {
        ProductObservationDraftRepository drafts = mock(ProductObservationDraftRepository.class);
        ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "青葉", "大麥白 107", "商品圖", NOW, NOW.plusSeconds(600));
        ReflectionTestUtils.setField(draft, "id", 31L);
        when(drafts.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                ACTOR, ProductObservationDraft.Status.PENDING_PURPOSE, NOW))
                .thenReturn(Optional.of(draft));
        when(annotations.save(any())).thenAnswer(invocation -> {
            ObjectAnnotation annotation = invocation.getArgument(0);
            ReflectionTestUtils.setField(annotation, "id", 32L);
            return annotation;
        });
        ProductExperienceService service = new ProductExperienceService(
                drafts, annotations, mock(UserKnowledgeService.class), graph,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ProductExperienceService.RecordedExperience recorded;
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            recorded = service.completeLatestDraft();
        }

        assertThat(draft.getStatus()).isEqualTo(ProductObservationDraft.Status.RESOLVED);
        assertThat(recorded.annotation().getDetail())
                .isEqualTo("商品圖片辨識資料：青葉 水泥漆 大麥白 107");
        verify(drafts).save(draft);
        verify(graph).indexObjectAnnotation(org.mockito.ArgumentMatchers.eq(32L), any());
    }
}
