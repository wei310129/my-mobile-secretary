package com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class TaggedLifeRecord extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private RecordType recordType;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false)
    private Instant occurredAt;
    private String details;
    @Column(nullable = false)
    private Instant createdAt;

    protected TaggedLifeRecord() {}

    public static TaggedLifeRecord create(RecordType type, String title, Instant occurredAt,
                                          String details, Instant now) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("life record title is required");
        TaggedLifeRecord record = new TaggedLifeRecord();
        record.recordType = type == null ? RecordType.OTHER : type;
        record.title = title.strip();
        record.occurredAt = java.util.Objects.requireNonNull(occurredAt);
        record.details = details == null || details.isBlank() ? null : details.strip();
        record.createdAt = now;
        return record;
    }

    public Long getId() { return id; }
    public RecordType getRecordType() { return recordType; }
    public String getTitle() { return title; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getDetails() { return details; }

    public enum RecordType {
        USER_UTTERANCE, APPLICATION, PURCHASE, PROMOTION, ACTIVITY, SCHEDULE,
        TASK, REMINDER, PLACE, KNOWLEDGE, HEALTH, WORK, OTHER
    }
}
