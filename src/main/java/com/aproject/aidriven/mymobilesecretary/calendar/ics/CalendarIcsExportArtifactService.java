package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarIcsExportArtifactService {

    private static final long TTL_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final MediaObjectStorage storage;
    private final Clock clock;

    public CalendarIcsExportArtifactService(
            JdbcTemplate jdbc, MediaObjectStorage storage, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    public CalendarIcsExportArtifact create(CalendarIcsExportRequest request) {
        requireRequest(request);
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        UUID sourceOwner = requireAuthorizedProjection(
                request.planId(), context);
        String requestHash = hash(request.requestId());
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = hash(rawToken);
        UUID id = UUID.randomUUID();
        String storageKey = id.toString().substring(0, 2) + "/" + id;
        Instant now = Instant.now(clock);
        Instant expires = now.plusSeconds(TTL_SECONDS);
        storage.put(storageKey, request.content());
        try {
            jdbc.update(
                    """
                    INSERT INTO calendar_ics_export_artifact (
                        id, plan_id, window_start, window_end_exclusive,
                        export_profile, loss_report, storage_key,
                        content_hash, token_hash, operation_request_hash,
                        artifact_status, expires_at, consumed_at, revoked_at,
                        created_at, workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?,
                        NULL, NULL, ?, ?, ?, ?)
                    """,
                    id,
                    request.planId(),
                    request.windowStart(),
                    request.windowEndExclusive(),
                    request.profile().name(),
                    request.lossReport(),
                    storageKey,
                    hash(request.content()),
                    tokenHash,
                    requestHash,
                    Timestamp.from(expires),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId(),
                    sourceOwner);
        } catch (DuplicateKeyException duplicate) {
            storage.delete(storageKey);
            throw new SecurityException("ICS export request was already used");
        } catch (RuntimeException failure) {
            storage.delete(storageKey);
            throw failure;
        }
        return new CalendarIcsExportArtifact(
                id, rawToken, expires, request.lossReport());
    }

    public byte[] download(String rawToken, boolean irreversibleAcknowledged) {
        if (!irreversibleAcknowledged) {
            throw new SecurityException(
                    "ICS delivery requires irreversible-download acknowledgement");
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        String tokenHash = hash(rawToken);
        List<Row> rows = jdbc.query(
                """
                SELECT id, plan_id, storage_key, content_hash, expires_at,
                       artifact_status
                FROM calendar_ics_export_artifact
                WHERE token_hash = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> new Row(
                        row.getObject("id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        row.getString("storage_key"),
                        row.getString("content_hash"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getString("artifact_status")),
                tokenHash,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw denied();
        }
        Row row = rows.getFirst();
        Instant now = Instant.now(clock);
        if (!"ACTIVE".equals(row.status()) || !now.isBefore(row.expiresAt())) {
            throw denied();
        }
        requireAuthorizedProjection(row.planId(), context);
        int consumed = jdbc.update(
                """
                UPDATE calendar_ics_export_artifact
                SET artifact_status = 'USED', consumed_at = ?
                WHERE id = ? AND artifact_status = 'ACTIVE'
                  AND consumed_at IS NULL AND revoked_at IS NULL
                  AND expires_at > ?
                """,
                Timestamp.from(now),
                row.id(),
                Timestamp.from(now));
        if (consumed != 1) {
            throw denied();
        }
        byte[] content = storage.read(row.storageKey());
        if (!MessageDigest.isEqual(
                row.contentHash().getBytes(StandardCharsets.US_ASCII),
                hash(content).getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("ICS artifact integrity check failed");
        }
        return content;
    }

    private UUID requireAuthorizedProjection(
            UUID planId, WorkspaceContext context) {
        List<UUID> owners = jdbc.queryForList(
                """
                SELECT plan.created_by_user_id
                FROM calendar_plan plan
                LEFT JOIN calendar_plan_ownership ownership
                  ON ownership.plan_id = plan.id
                 AND ownership.workspace_id = plan.workspace_id
                 AND ownership.source_created_by_user_id =
                        plan.created_by_user_id
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.status = 'ACTIVE'
                  AND (
                    COALESCE(ownership.owner_user_id,
                             plan.created_by_user_id) = ?
                    OR EXISTS (
                        SELECT 1 FROM calendar_adoption adoption
                        WHERE adoption.plan_id = plan.id
                          AND adoption.workspace_id = plan.workspace_id
                          AND adoption.created_by_user_id = ?
                          AND adoption.status = 'ACTIVE'))
                """,
                UUID.class,
                planId,
                context.workspaceId(),
                context.actorId(),
                context.actorId());
        if (owners.size() != 1) {
            throw denied();
        }
        return owners.getFirst();
    }

    private static void requireRequest(CalendarIcsExportRequest request) {
        if (request == null
                || request.planId() == null
                || request.windowStart() == null
                || request.windowEndExclusive() == null
                || request.profile() == null
                || request.lossReport() == null
                || request.content() == null
                || request.content().length == 0
                || !request.windowEndExclusive().isAfter(request.windowStart())
                || ChronoUnit.DAYS.between(
                                request.windowStart(),
                                request.windowEndExclusive())
                        > 366) {
            throw new IllegalArgumentException(
                    "A bounded ICS export request is required");
        }
        hash(request.requestId());
    }

    private static SecurityException denied() {
        return new SecurityException("ICS artifact is unavailable");
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hash input is required");
        }
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Row(
            UUID id,
            UUID planId,
            String storageKey,
            String contentHash,
            Instant expiresAt,
            String status) {}
}
