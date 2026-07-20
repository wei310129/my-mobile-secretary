package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SemanticTagGraphFlowTest extends IntegrationTestBase {

    @Autowired private SemanticTagGraphService graphService;
    @Autowired private TaggedRecordQueryService queryService;
    @Autowired private PriceRecordService priceRecordService;
    @Autowired private TaskService taskService;
    @Autowired private ItemService itemService;

    @Test
    void traversesProductCategoryAndMultiLevelBenefitRelations() {
        priceRecordService.record("冰箱", "全國電子", 30000, 1,
                LocalDate.parse("2026-07-10"));
        graphService.relate("節能補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.RelationType.IS_A, "政府補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.SourceType.USER);
        graphService.relate("政府補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.RelationType.IS_A, "補助", SemanticTag.Kind.BENEFIT,
                SemanticTagEdge.SourceType.USER);
        graphService.recordLifeEvent(TaggedLifeRecord.RecordType.APPLICATION,
                "申請節能補助", Instant.parse("2026-07-12T02:00:00Z"), null,
                List.of(new SemanticTagGraphService.TagSpec(
                        "節能補助", SemanticTag.Kind.BENEFIT,
                        SemanticTagEdge.SourceType.USER)));

        assertThat(queryService.query("電器", null, null, "PURCHASE"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .containsExactly("冰箱");
        assertThat(queryService.query("政府補助", null, null, "APPLICATION"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .containsExactly("申請節能補助");
        assertThat(queryService.query("補助", null, null, "APPLICATION"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .containsExactly("申請節能補助");
    }

    @Test
    void applicationLifecycleEventsAreRecordedAndQueryableByTags() {
        var task = taskService.createTask(
                "申請政府補助", null, TaskPriority.NORMAL, null);
        taskService.cancelTask(task.getId());
        itemService.addShoppingItems(List.of("牛奶"));

        assertThat(queryService.query("取消", null, null, "TASK"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .contains("申請政府補助");
        assertThat(queryService.query("加入購物清單", null, null, "KNOWLEDGE"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .contains("牛奶");
    }

    @Test
    void publicProductTopicFindsRecordWithoutExactOcrTitle() {
        graphService.relate("水泥漆", SemanticTag.Kind.PRODUCT,
                SemanticTagEdge.RelationType.RELATED_TO,
                "居家修繕", SemanticTag.Kind.TOPIC,
                SemanticTagEdge.SourceType.SYSTEM_RULE);
        graphService.recordLifeEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "青葉水泥漆商品註記", Instant.parse("2026-07-19T02:00:00Z"),
                "客廳工程第二批",
                List.of(new SemanticTagGraphService.TagSpec(
                        "水泥漆", SemanticTag.Kind.PRODUCT,
                        SemanticTagEdge.SourceType.IMPORT)));

        assertThat(queryService.query("居家修繕", null, null, "KNOWLEDGE"))
                .extracting(TaggedRecordQueryService.TaggedRecordView::title)
                .containsExactly("青葉水泥漆商品註記");
    }
}
