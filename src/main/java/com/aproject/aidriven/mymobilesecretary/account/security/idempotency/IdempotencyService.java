package com.aproject.aidriven.mymobilesecretary.account.security.idempotency;

import com.aproject.aidriven.mymobilesecretary.intent.application.SecretTextCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically reserves an external request key before any side effect. Only the request hash and
 * an optional encrypted response are retained; raw requests never enter this table.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final SecretTextCipher cipher;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbcTemplate, SecretTextCipher cipher,
                              IdempotencyProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public BeginResult begin(UUID workspaceId, UUID actorUserId, String channel,
                             String idempotencyKey, String requestBody) {
        requireId(workspaceId, "workspaceId");
        requireId(actorUserId, "actorUserId");
        String safeChannel = requireText(channel, "channel", 40).toUpperCase(java.util.Locale.ROOT);
        String safeKey = requireText(idempotencyKey, "idempotencyKey", 160);
        String requestHash = sha256(requestBody == null ? "" : requestBody);
        Instant now = Instant.now(clock);
        int inserted = jdbcTemplate.update("""
                INSERT INTO idempotency_record (
                    workspace_id, actor_user_id, channel, idempotency_key, request_hash,
                    status, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?)
                ON CONFLICT (workspace_id, actor_user_id, channel, idempotency_key) DO UPDATE
                SET request_hash = EXCLUDED.request_hash,
                    status = 'RESERVED',
                    response_action = NULL,
                    response_encrypted = NULL,
                    response_cipher_key_id = NULL,
                    created_at = EXCLUDED.created_at,
                    updated_at = EXCLUDED.updated_at,
                    expires_at = EXCLUDED.expires_at
                WHERE idempotency_record.expires_at <= EXCLUDED.created_at
                   OR (idempotency_record.status = 'FAILED'
                       AND idempotency_record.request_hash = EXCLUDED.request_hash)
                """, workspaceId, actorUserId, safeChannel, safeKey, requestHash,
                now, now, now.plus(properties.retention()));
        if (inserted == 1) {
            return new BeginResult(State.NEW, null, null);
        }

        Stored existing = jdbcTemplate.queryForObject("""
                SELECT request_hash, status, response_action, response_encrypted,
                       response_cipher_key_id
                FROM idempotency_record
                WHERE workspace_id = ? AND actor_user_id = ? AND channel = ? AND idempotency_key = ?
                """, IdempotencyService::mapStored,
                workspaceId, actorUserId, safeChannel, safeKey);
        if (existing == null || !MessageDigest.isEqual(
                existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            return new BeginResult(State.CONFLICT, null, null);
        }
        return switch (existing.status()) {
            case "RESERVED" -> new BeginResult(State.IN_PROGRESS, null, null);
            case "UNKNOWN" -> new BeginResult(State.RESULT_UNKNOWN,
                    existing.responseAction(), null);
            case "FAILED" -> new BeginResult(State.PREVIOUS_FAILED, existing.responseAction(), null);
            case "COMPLETED" -> replay(existing);
            default -> throw new IllegalStateException("Unknown idempotency status");
        };
    }

    /**
     * Crosses the point after which a business mutation may commit. UNKNOWN is intentionally
     * fail-closed: a crash or later bookkeeping failure must never make the request retryable.
     */
    @Transactional
    public void markExecutionStarted(UUID workspaceId, UUID actorUserId, String channel,
                                     String idempotencyKey) {
        int updated = jdbcTemplate.update("""
                UPDATE idempotency_record
                SET status = 'UNKNOWN', response_action = 'EXECUTION_STARTED', updated_at = ?
                WHERE workspace_id = ? AND actor_user_id = ? AND channel = ?
                    AND idempotency_key = ? AND status = 'RESERVED'
                """, Instant.now(clock), workspaceId, actorUserId, normalizeChannel(channel),
                idempotencyKey);
        requireTransition(updated, "start execution");
    }

    @Transactional
    public void complete(UUID workspaceId, UUID actorUserId, String channel,
                         String idempotencyKey, String responseAction, String responseBody) {
        Optional<SecretTextCipher.EncryptedText> encrypted = cipher.encrypt(responseBody);
        byte[] payload = encrypted.map(SecretTextCipher.EncryptedText::payload).orElse(null);
        String keyId = encrypted.map(SecretTextCipher.EncryptedText::keyId).orElse(null);
        int updated = jdbcTemplate.update("""
                UPDATE idempotency_record
                SET status = 'COMPLETED', response_action = ?, response_encrypted = ?,
                    response_cipher_key_id = ?, updated_at = ?
                WHERE workspace_id = ? AND actor_user_id = ? AND channel = ?
                    AND idempotency_key = ? AND status = 'UNKNOWN'
                """, optionalText(responseAction, 80), payload, keyId, Instant.now(clock),
                workspaceId, actorUserId, normalizeChannel(channel), idempotencyKey);
        requireTransition(updated, "complete execution");
    }

    /** Marks only a provably pre-execution failure as retryable. */
    @Transactional
    public void failBeforeExecution(UUID workspaceId, UUID actorUserId, String channel,
                                    String idempotencyKey, String reasonCode) {
        int updated = jdbcTemplate.update("""
                UPDATE idempotency_record
                SET status = 'FAILED', response_action = ?, updated_at = ?
                WHERE workspace_id = ? AND actor_user_id = ? AND channel = ?
                    AND idempotency_key = ? AND status = 'RESERVED'
                """, optionalText(reasonCode, 80), Instant.now(clock), workspaceId, actorUserId,
                normalizeChannel(channel), idempotencyKey);
        requireTransition(updated, "fail reservation");
    }

    /** Adds a bounded diagnostic while preserving the terminal UNKNOWN state. */
    @Transactional
    public void recordUnknownResult(UUID workspaceId, UUID actorUserId, String channel,
                                    String idempotencyKey, String reasonCode) {
        jdbcTemplate.update("""
                UPDATE idempotency_record
                SET response_action = ?, updated_at = ?
                WHERE workspace_id = ? AND actor_user_id = ? AND channel = ?
                    AND idempotency_key = ? AND status = 'UNKNOWN'
                """, optionalText(reasonCode, 80), Instant.now(clock), workspaceId, actorUserId,
                normalizeChannel(channel), idempotencyKey);
    }

    @Transactional
    public int purgeExpired() {
        return jdbcTemplate.update("DELETE FROM idempotency_record WHERE expires_at < ?",
                Instant.now(clock));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private BeginResult replay(Stored existing) {
        if (existing.encryptedResponse() == null || existing.keyId() == null) {
            return new BeginResult(State.COMPLETED_NO_REPLAY, existing.responseAction(), null);
        }
        Optional<String> response = cipher.decrypt(new SecretTextCipher.EncryptedText(
                existing.keyId(), existing.encryptedResponse()));
        return response.map(body -> new BeginResult(State.REPLAY_AVAILABLE,
                        existing.responseAction(), body))
                .orElseGet(() -> new BeginResult(State.COMPLETED_NO_REPLAY,
                        existing.responseAction(), null));
    }

    private static Stored mapStored(ResultSet rs, int rowNum) throws SQLException {
        return new Stored(rs.getString("request_hash"), rs.getString("status"),
                rs.getString("response_action"), rs.getBytes("response_encrypted"),
                rs.getString("response_cipher_key_id"));
    }

    private static void requireId(UUID id, String field) {
        Objects.requireNonNull(id, field + " is required");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return stripped;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    private static String normalizeChannel(String channel) {
        return requireText(channel, "channel", 40).toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireTransition(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Idempotency could not " + operation);
        }
    }

    public enum State {
        NEW,
        IN_PROGRESS,
        REPLAY_AVAILABLE,
        COMPLETED_NO_REPLAY,
        PREVIOUS_FAILED,
        RESULT_UNKNOWN,
        CONFLICT
    }

    public record BeginResult(State state, String responseAction, String responseBody) {
    }

    private record Stored(String requestHash, String status, String responseAction,
                          byte[] encryptedResponse, String keyId) {
    }
}
