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
public class SemanticTagEdge extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long fromTagId;
    @Column(nullable = false)
    private Long toTagId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private RelationType relationType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SourceType sourceType;
    @Column(nullable = false)
    private Instant createdAt;

    protected SemanticTagEdge() {}

    public static SemanticTagEdge create(Long from, Long to, RelationType relation,
                                         SourceType source, Instant now) {
        if (from == null || to == null || from.equals(to)) {
            throw new IllegalArgumentException("tag edge requires two distinct tags");
        }
        SemanticTagEdge edge = new SemanticTagEdge();
        edge.fromTagId = from;
        edge.toTagId = to;
        edge.relationType = java.util.Objects.requireNonNull(relation);
        edge.sourceType = java.util.Objects.requireNonNull(source);
        edge.createdAt = now;
        return edge;
    }

    public Long getFromTagId() { return fromTagId; }
    public Long getToTagId() { return toTagId; }
    public RelationType getRelationType() { return relationType; }

    public enum RelationType { IS_A, RELATED_TO, PART_OF, ELIGIBLE_FOR, PROVIDED_BY }
    public enum SourceType { USER, SYSTEM_RULE, IMPORT }
}
