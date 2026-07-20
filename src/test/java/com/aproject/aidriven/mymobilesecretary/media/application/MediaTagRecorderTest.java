package com.aproject.aidriven.mymobilesecretary.media.application;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaTagRecorderTest {

    @Test
    void uploadCreatesGenericLifeRecordWithoutLeakingOriginalFilename() {
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        UniversalLifeRecordService life = mock(UniversalLifeRecordService.class);
        MediaTagRecorder recorder = new MediaTagRecorder(graph, life);
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        StoredMedia media = StoredMedia.create(
                StoredMedia.SourceType.APP, StoredMedia.MediaKind.IMAGE, "敏感病歷照片",
                "A123456789-診斷.png", "image/png", 8, "0".repeat(64),
                "aa/00000000-0000-0000-0000-000000000000", null, now);

        recorder.stored(media);

        verify(graph).indexStoredMedia(eq(media), anyList());
        verify(life).recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "上傳一個圖片原始檔", now, List.of("原始檔案", "圖片", "上傳"));
    }
}
