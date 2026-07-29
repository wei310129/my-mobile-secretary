package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import java.time.Instant;

public record UserKnowledgeFactUpdatedEvent(Long factId, String subject, Instant updatedAt) {}
