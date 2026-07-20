package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.TaggedRecordQueryService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagGraphIntentHandlerTest {

    @Test
    void savesOnlyExplicitTypedRelationAsUserSource() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        TagGraphIntentHandler handler = new TagGraphIntentHandler(
                graph, mock(TaggedRecordQueryService.class));
        IntentOptions options = IntentOptions.empty().withTagRelation(
                "政府補助", "IS_A", "BENEFIT", "BENEFIT");
        IntentCommand command = new IntentCommand(IntentCommand.Type.UPSERT_TAG_RELATION,
                "節能補助", null, null, null, null, null, null,
                null, null, null, null, null, options);

        IntentResult result = handler.handle("節能補助是政府補助的一種", command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.TAG_RELATION_SAVED);
        verify(graph).relate("節能補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.RelationType.IS_A, "政府補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.SourceType.USER);
    }

    @Test
    void stripsNaturalLookupPrefixAndReturnsStoredAnnotation() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        TaggedRecordQueryService query = mock(TaggedRecordQueryService.class);
        when(query.query("青葉水泥漆 Emulsion Paint 大麥白 107", null, null, null))
                .thenReturn(List.of(new TaggedRecordQueryService.TaggedRecordView(
                        "KNOWLEDGE", "青葉水泥漆", Instant.parse("2026-07-20T00:17:10Z"),
                        "這是家裡油漆顏色")));
        TagGraphIntentHandler handler = new TagGraphIntentHandler(graph, query);
        IntentCommand command = new IntentCommand(IntentCommand.Type.ASK_TAGGED_RECORDS,
                "幫我查青葉水泥漆 Emulsion Paint 大麥白 107",
                null, null, null, null, null, null, null, null, null, null, null,
                IntentOptions.empty());

        IntentResult result = handler.handle("幫我查青葉水泥漆", command);

        assertThat(result.message()).contains("青葉水泥漆", "這是家裡油漆顏色");
        verify(query).query("青葉水泥漆 Emulsion Paint 大麥白 107", null, null, null);
    }
}
