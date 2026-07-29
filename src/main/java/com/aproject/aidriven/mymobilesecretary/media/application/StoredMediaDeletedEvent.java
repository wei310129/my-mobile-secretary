package com.aproject.aidriven.mymobilesecretary.media.application;

import java.time.Instant;
import java.util.UUID;

public record StoredMediaDeletedEvent(
        Long mediaId, UUID workspaceId, UUID actorId, Instant deletedAt) {}
