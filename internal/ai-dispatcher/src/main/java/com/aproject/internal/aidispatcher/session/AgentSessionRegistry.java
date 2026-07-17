package com.aproject.internal.aidispatcher.session;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentSessionRegistry {

    public static final String DEVELOPMENT_SESSION_KEY = "development-main";
    public static final String DEVELOPMENT_SESSION_DISPLAY_NAME = "開發主要對話";
    public static final String CODEX_PROVIDER = "CODEX_DESKTOP";

    private static final String LANE_KEY = "CODEX_DEVELOPMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AgentSessionRegistry(JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public SessionBinding developmentSession() {
        List<SessionBinding> rows = jdbcTemplate.query("""
                SELECT session_key, display_name, provider, external_session_id, status,
                       version, bound_at, last_verified_at, updated_at
                FROM agent_session
                WHERE session_key = ?
                """, (resultSet, rowNumber) -> mapBinding(resultSet), DEVELOPMENT_SESSION_KEY);
        return requireOneBinding(rows);
    }

    public SessionBinding bindDevelopmentSession(String externalSessionId) {
        return bindDevelopmentSession(new BindDevelopmentSessionCommand(
                externalSessionId, null, "internal-api", "Legacy binding operation"));
    }

    public SessionBinding bindDevelopmentSession(BindDevelopmentSessionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        String externalSessionId = requireOpaqueId(command.externalSessionId());
        String actorId = requireText(command.actorId(), "actorId", 200);
        String reason = optionalText(command.reason(), "reason", 500);
        requireExpectedVersion(command.expectedVersion());

        SessionBinding result = transactionTemplate.execute(status -> bindLocked(
                externalSessionId, command.expectedVersion(), actorId, reason));
        if (result == null) {
            throw new IllegalStateException("Session binding transaction returned no result");
        }
        return result;
    }

    public SessionBinding unbindDevelopmentSession(UnbindDevelopmentSessionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        String actorId = requireText(command.actorId(), "actorId", 200);
        String reason = optionalText(command.reason(), "reason", 500);
        requireExpectedVersion(command.expectedVersion());

        SessionBinding result = transactionTemplate.execute(status -> unbindLocked(
                command.expectedVersion(), actorId, reason));
        if (result == null) {
            throw new IllegalStateException("Session unbinding transaction returned no result");
        }
        return result;
    }

    private SessionBinding bindLocked(String externalSessionId,
                                      Long expectedVersion,
                                      String actorId,
                                      String reason) {
        LaneRow lane = lockLane();
        requireNoActiveRun(lane);
        SessionBinding current = lockDevelopmentSession();
        requireExpectedVersion(current, expectedVersion);

        Instant now = Instant.now(clock);
        BindingAction action = bindingAction(current, externalSessionId);
        Instant boundAt = action == BindingAction.REVERIFIED && current.boundAt() != null
                ? current.boundAt()
                : now;
        int updated = jdbcTemplate.update("""
                UPDATE agent_session
                SET external_session_id = ?, status = 'READY', bound_at = ?,
                    last_verified_at = ?, version = version + 1, updated_at = ?
                WHERE session_key = ? AND version = ?
                """, externalSessionId, timestamp(boundAt), timestamp(now), timestamp(now),
                DEVELOPMENT_SESSION_KEY, current.version());
        requireSingleSessionUpdate(updated);

        SessionBinding result = lockDevelopmentSession();
        insertAudit(action, current.externalSessionId(), externalSessionId,
                result.version(), actorId, reason, now);
        resumeSessionReadinessPause(lane, now);
        return result;
    }

    private SessionBinding unbindLocked(Long expectedVersion, String actorId, String reason) {
        LaneRow lane = lockLane();
        requireNoActiveRun(lane);
        SessionBinding current = lockDevelopmentSession();
        requireExpectedVersion(current, expectedVersion);
        if (current.status() == AgentSessionStatus.UNBOUND) {
            return current;
        }

        Instant now = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                UPDATE agent_session
                SET external_session_id = NULL, status = 'UNBOUND', bound_at = NULL,
                    last_verified_at = NULL, version = version + 1, updated_at = ?
                WHERE session_key = ? AND version = ?
                """, timestamp(now), DEVELOPMENT_SESSION_KEY, current.version());
        requireSingleSessionUpdate(updated);
        pauseForMissingSession(now);

        SessionBinding result = lockDevelopmentSession();
        insertAudit(BindingAction.UNBOUND, current.externalSessionId(), null,
                result.version(), actorId, reason, now);
        return result;
    }

    private LaneRow lockLane() {
        List<LaneRow> rows = jdbcTemplate.query("""
                SELECT state, active_run_id, last_error_code
                FROM dispatcher_lane
                WHERE lane_key = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LaneRow(
                        resultSet.getString("state"),
                        resultSet.getObject("active_run_id", UUID.class),
                        resultSet.getString("last_error_code")), LANE_KEY);
        if (rows.size() != 1) {
            throw new IllegalStateException("Dispatcher lane is missing or duplicated");
        }
        return rows.getFirst();
    }

    private SessionBinding lockDevelopmentSession() {
        List<SessionBinding> rows = jdbcTemplate.query("""
                SELECT session_key, display_name, provider, external_session_id, status,
                       version, bound_at, last_verified_at, updated_at
                FROM agent_session
                WHERE session_key = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapBinding(resultSet), DEVELOPMENT_SESSION_KEY);
        return requireOneBinding(rows);
    }

    private void resumeSessionReadinessPause(LaneRow lane, Instant now) {
        if (!"PAUSED".equals(lane.state())
                || !"SESSION_NOT_READY".equals(lane.lastErrorCode())) {
            return;
        }
        Long pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = 'PENDING'
                """, Long.class);
        String resumedState = pending != null && pending > 0 ? "WAITING" : "IDLE";
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = ?, paused_reason = NULL, last_error_code = NULL,
                    version = version + 1, updated_at = ?
                WHERE lane_key = ? AND state = 'PAUSED' AND active_run_id IS NULL
                  AND last_error_code = 'SESSION_NOT_READY'
                """, resumedState, timestamp(now), LANE_KEY);
        if (updated != 1) {
            throw new SessionBindingConflictException(
                    "Session readiness pause changed while the binding was updated");
        }
    }

    private void pauseForMissingSession(Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'PAUSED', active_run_id = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    last_error_code = 'SESSION_NOT_READY',
                    paused_reason = 'The configured Codex session is not bound and ready',
                    version = version + 1, updated_at = ?
                WHERE lane_key = ? AND active_run_id IS NULL
                """, timestamp(now), LANE_KEY);
        if (updated != 1) {
            throw new SessionBindingConflictException(
                    "Dispatcher lane changed while the session was unbound");
        }
    }

    private void insertAudit(BindingAction action,
                             String previousExternalSessionId,
                             String externalSessionId,
                             long version,
                             String actorId,
                             String reason,
                             Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO agent_session_binding_audit (
                    audit_id, session_key, binding_version, action,
                    previous_external_session_id, external_session_id,
                    actor_id, reason, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), DEVELOPMENT_SESSION_KEY, version, action.name(),
                previousExternalSessionId, externalSessionId, actorId, reason,
                timestamp(occurredAt));
    }

    private static SessionBinding mapBinding(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new SessionBinding(
                resultSet.getString("session_key"),
                resultSet.getString("display_name"),
                resultSet.getString("provider"),
                resultSet.getString("external_session_id"),
                AgentSessionStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                instant(resultSet.getTimestamp("bound_at")),
                instant(resultSet.getTimestamp("last_verified_at")),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static SessionBinding requireOneBinding(List<SessionBinding> rows) {
        if (rows.size() != 1) {
            throw new IllegalStateException("Development session registry row is missing or duplicated");
        }
        return rows.getFirst();
    }

    private static void requireNoActiveRun(LaneRow lane) {
        if (lane.activeRunId() != null) {
            throw new SessionBindingConflictException(
                    "Cannot change the session binding while a run outcome may still be active");
        }
    }

    private static void requireExpectedVersion(SessionBinding current, Long expectedVersion) {
        if (expectedVersion != null && current.version() != expectedVersion) {
            throw new SessionBindingConflictException(
                    "Session binding version changed; expected " + expectedVersion
                            + " but found " + current.version());
        }
    }

    private static void requireExpectedVersion(Long expectedVersion) {
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    private static void requireSingleSessionUpdate(int updated) {
        if (updated != 1) {
            throw new SessionBindingConflictException(
                    "Session binding changed concurrently");
        }
    }

    private static BindingAction bindingAction(SessionBinding current, String externalSessionId) {
        if (current.externalSessionId() == null) {
            return BindingAction.BOUND;
        }
        if (current.externalSessionId().equals(externalSessionId)) {
            return BindingAction.REVERIFIED;
        }
        return BindingAction.REBOUND;
    }

    private static String requireOpaqueId(String value) {
        String result = requireText(value, "externalSessionId", 200);
        if (DEVELOPMENT_SESSION_DISPLAY_NAME.equals(result)) {
            throw new IllegalArgumentException(
                    "externalSessionId must be an opaque technical id, not the display name");
        }
        if (result.length() < 8) {
            throw new IllegalArgumentException("externalSessionId is too short");
        }
        if (result.codePoints().anyMatch(character ->
                Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException(
                    "externalSessionId must be an opaque technical id without whitespace");
        }
        return result;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return stripped;
    }

    private static String optionalText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, name, maximumLength);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private enum BindingAction {
        BOUND,
        REBOUND,
        REVERIFIED,
        UNBOUND
    }

    private record LaneRow(String state, UUID activeRunId, String lastErrorCode) {
    }
}
