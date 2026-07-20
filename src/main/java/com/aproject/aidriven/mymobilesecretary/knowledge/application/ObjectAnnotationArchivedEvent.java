package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import java.time.Instant;

/** 使用者確認封存一筆知識註記；資料保留為可恢復 tombstone。 */
public record ObjectAnnotationArchivedEvent(Long annotationId, String subject, Instant archivedAt) {
}
