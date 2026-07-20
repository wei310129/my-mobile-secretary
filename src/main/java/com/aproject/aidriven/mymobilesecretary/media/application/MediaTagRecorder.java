package com.aproject.aidriven.mymobilesecretary.media.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Keeps media lifecycle in the universal tag system without exposing filenames as tags. */
@Component
public class MediaTagRecorder {

    private final SemanticTagGraphService tagGraphService;
    private final UniversalLifeRecordService lifeRecordService;

    public MediaTagRecorder(SemanticTagGraphService tagGraphService,
                            UniversalLifeRecordService lifeRecordService) {
        this.tagGraphService = tagGraphService;
        this.lifeRecordService = lifeRecordService;
    }

    public void stored(StoredMedia media) {
        String kind = media.getMediaKind() == StoredMedia.MediaKind.IMAGE ? "圖片" : "文件";
        tagGraphService.indexStoredMedia(media, List.of(
                tag("原始檔案", SemanticTag.Kind.TOPIC),
                tag(kind, SemanticTag.Kind.CATEGORY),
                tag(media.getSourceType().name(), SemanticTag.Kind.ACTIVITY)));
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "上傳一個%s原始檔".formatted(kind), media.getCreatedAt(),
                List.of("原始檔案", kind, "上傳"));
    }

    public void classified(StoredMedia media, String classification) {
        tagGraphService.indexStoredMedia(media,
                List.of(tag(classification, SemanticTag.Kind.TOPIC)));
    }

    public void deleted(StoredMedia media, Instant occurredAt) {
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "刪除一個原始檔", occurredAt, List.of("原始檔案", "刪除"));
    }

    private static SemanticTagGraphService.TagSpec tag(String name, SemanticTag.Kind kind) {
        return new SemanticTagGraphService.TagSpec(
                name, kind, SemanticTagEdge.SourceType.SYSTEM_RULE);
    }
}
