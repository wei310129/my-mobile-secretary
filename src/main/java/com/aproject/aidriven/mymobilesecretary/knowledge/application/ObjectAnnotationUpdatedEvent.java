package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import java.time.Instant;

/** User-visible edit of a durable private knowledge record. */
public record ObjectAnnotationUpdatedEvent(Long annotationId, String subject, Instant updatedAt) {
}
