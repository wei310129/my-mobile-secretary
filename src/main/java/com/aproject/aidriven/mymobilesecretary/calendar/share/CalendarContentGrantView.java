package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarContentGrantView(
        UUID id,
        UUID shareId,
        ContentKind contentKind,
        Status status,
        long revision) {

    public enum ContentKind {
        ATTACHMENT,
        KNOWLEDGE_EXCERPT
    }

    public enum Status {
        ACTIVE,
        REVOKED
    }
}
