package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductDraftCompletionConversationServiceTest {
    @Test
    void explicitCompletionRunsMutationAndDoesNotAskAboutRetention() {
        ProductExperienceService experience = mock(ProductExperienceService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "青葉", "大麥白 107", "商品圖",
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-27T16:00:00Z"));
        when(experience.completeLatestDraft()).thenReturn(
                new ProductExperienceService.RecordedExperience(draft, null, null));
        Runnable mutation = mock(Runnable.class);

        var result = new ProductDraftCompletionConversationService(experience)
                .answer("完成這筆草稿", mutation).orElseThrow();

        assertThat(result.message()).contains("已完成草稿", "可長期查詢的知識紀錄")
                .doesNotContain("要保留幾天", "提醒時間");
        verify(mutation).run();
        verify(experience).completeLatestDraft();
    }

    @Test
    void unrelatedSentenceFallsThroughWithoutMutation() {
        ProductExperienceService experience = mock(ProductExperienceService.class);
        Runnable mutation = mock(Runnable.class);

        assertThat(new ProductDraftCompletionConversationService(experience)
                .answer("完成拿包裹待辦", mutation)).isEmpty();

        verify(mutation, never()).run();
        verify(experience, never()).completeLatestDraft();
    }
}
