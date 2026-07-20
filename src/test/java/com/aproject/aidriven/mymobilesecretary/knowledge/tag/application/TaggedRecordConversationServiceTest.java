package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaggedRecordConversationServiceTest {

    @Test
    void paintLookupReturnsAnnotationBeforeGenericPurchaseRouting() {
        TaggedRecordQueryService query = mock(TaggedRecordQueryService.class);
        ConversationContextService context = mock(ConversationContextService.class);
        when(query.query("青葉水泥漆", null, null, null)).thenReturn(List.of(
                new TaggedRecordQueryService.TaggedRecordView(
                        "KNOWLEDGE", "青葉 青葉水泥漆 Emulsion Paint 大麥白 107",
                        Instant.parse("2026-07-20T00:17:10Z"), "這是家裡油漆顏色", 91L)));
        TaggedRecordConversationService service = new TaggedRecordConversationService(query);
        service.setConversationContextService(context);

        var result = service.answer("幫我查青葉水泥漆").orElseThrow();

        assertThat(result.message()).contains("青葉水泥漆", "1. ", "家裡油漆顏色");
        verify(query).query("青葉水泥漆", null, null, null);
        verify(context).rememberObjectAnnotationList(List.of(91L));
    }

    @Test
    void displayedOrdinalKeepsPlaceholderForNonDeletableRecord() {
        TaggedRecordQueryService query = mock(TaggedRecordQueryService.class);
        ConversationContextService context = mock(ConversationContextService.class);
        when(query.query("油漆", null, null, null)).thenReturn(List.of(
                new TaggedRecordQueryService.TaggedRecordView(
                        "PURCHASE", "油漆", Instant.parse("2026-07-21T00:00:00Z"), "500 元"),
                new TaggedRecordQueryService.TaggedRecordView(
                        "KNOWLEDGE", "油漆色號", Instant.parse("2026-07-20T00:00:00Z"),
                        "大麥白", 92L)));
        TaggedRecordConversationService service = new TaggedRecordConversationService(query);
        service.setConversationContextService(context);

        var result = service.answer("查油漆").orElseThrow();

        assertThat(result.message()).contains("1. ", "2. ");
        verify(context).rememberObjectAnnotationList(List.of(0L, 92L));
    }

    @Test
    void lookupWithoutStoredMatchFallsThrough() {
        TaggedRecordQueryService query = mock(TaggedRecordQueryService.class);
        when(query.query("不存在的標籤", null, null, null)).thenReturn(List.of());

        assertThat(new TaggedRecordConversationService(query).answer("幫我查不存在的標籤"))
                .isEmpty();
    }

    @Test
    void explicitPurchaseLookupFallsThroughDespiteMatchingTag() {
        TaggedRecordQueryService query = mock(TaggedRecordQueryService.class);
        when(query.query("油漆購買價格和店家", null, null, null)).thenReturn(List.of(
                new TaggedRecordQueryService.TaggedRecordView(
                        "KNOWLEDGE", "青葉水泥漆", Instant.parse("2026-07-20T00:17:10Z"),
                        "家裡油漆顏色")));

        assertThat(new TaggedRecordConversationService(query).answer("幫我查油漆購買價格和店家"))
                .isEmpty();
    }
}
