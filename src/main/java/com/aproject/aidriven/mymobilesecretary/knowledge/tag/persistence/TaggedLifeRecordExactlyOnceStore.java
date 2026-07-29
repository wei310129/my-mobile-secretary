package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaggedLifeRecordExactlyOnceStore {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;

    public TaggedLifeRecordExactlyOnceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RecordResult insertOrVerify(
            TaggedLifeRecord.RecordType type,
            String title,
            Instant occurredAt,
            String details,
            Instant createdAt,
            String sourceEventKeyHash,
            String sourcePayloadHash) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Life record event identity requires tenant scope");
        }
        TaggedLifeRecord.RecordType safeType =
                Objects.requireNonNull(type, "record type is required");
        String safeTitle = requireTitle(title);
        Instant safeOccurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
        Instant safeCreatedAt = Objects.requireNonNull(createdAt, "createdAt is required");
        String safeDetails = details == null || details.isBlank() ? null : details.strip();
        String safeEventKeyHash = requireSha256(sourceEventKeyHash, "source event key hash");
        String safePayloadHash = requireSha256(sourcePayloadHash, "source payload hash");

        List<Long> inserted =
                jdbc.query(
                        """
                        INSERT INTO tagged_life_record (
                            workspace_id, created_by_user_id, record_type, title,
                            occurred_at, details, created_at,
                            source_event_key_hash, source_payload_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (
                            workspace_id, created_by_user_id, source_event_key_hash)
                        DO NOTHING
                        RETURNING id
                        """,
                        (row, index) -> row.getLong("id"),
                        context.workspaceId(),
                        context.actorId(),
                        safeType.name(),
                        safeTitle,
                        Timestamp.from(safeOccurredAt),
                        safeDetails,
                        Timestamp.from(safeCreatedAt),
                        safeEventKeyHash,
                        safePayloadHash);
        if (inserted.size() == 1) {
            return new RecordResult(inserted.getFirst(), true);
        }

        List<ExistingIdentity> existing =
                jdbc.query(
                        """
                        SELECT id, source_payload_hash
                        FROM tagged_life_record
                        WHERE workspace_id = ?
                          AND created_by_user_id = ?
                          AND source_event_key_hash = ?
                        """,
                        (row, index) ->
                                new ExistingIdentity(
                                        row.getLong("id"),
                                        row.getString("source_payload_hash")),
                        context.workspaceId(),
                        context.actorId(),
                        safeEventKeyHash);
        if (existing.size() != 1) {
            throw new IllegalStateException(
                    "Life record event identity arbitration produced no row");
        }
        ExistingIdentity identity = existing.getFirst();
        if (!safePayloadHash.equals(identity.payloadHash())) {
            throw new BusinessException(
                    "LIFE_RECORD_EVENT_IDENTITY_CONFLICT",
                    "The life record event identity was already used for a different payload");
        }
        return new RecordResult(identity.recordId(), false);
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("life record title is required");
        }
        String safe = value.strip();
        if (safe.length() > 200) {
            throw new IllegalArgumentException("life record title exceeds 200 characters");
        }
        return safe;
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    private record ExistingIdentity(long recordId, String payloadHash) {}

    public record RecordResult(long recordId, boolean inserted) {}
}
