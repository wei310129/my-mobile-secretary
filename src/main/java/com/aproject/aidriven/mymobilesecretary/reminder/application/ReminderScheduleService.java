package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis-backed reliable delayed reminder queue.
 *
 * <p>Each workspace owns five Redis structures: a ready sorted set, a processing lease sorted
 * set, a lease-token hash, a failure-count hash and a dead-letter sorted set. New workspace keys
 * share one Redis Cluster hash tag, so every Lua state transition stays in one hash slot. The
 * legacy workspace keeps {@code reminder:schedule} as its ready key for backward compatibility.
 *
 * <p>Ready members keep the original wire format:
 *
 * <ul>
 *   <li>{@code DUE:{taskId}}</li>
 *   <li>{@code ESC:{reminderId}:{attempt}}</li>
 * </ul>
 */
@Service
public class ReminderScheduleService {

    static final String LEGACY_QUEUE_KEY = "reminder:schedule";

    private static final RedisScript<List> CLAIM_SCRIPT = listScript("""
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1],
                'LIMIT', 0, tonumber(ARGV[4]))
            local claimed = {}
            for index, member in ipairs(due) do
                if redis.call('ZREM', KEYS[1], member) == 1 then
                    local token = ARGV[3] .. ':' .. tostring(index)
                    redis.call('ZADD', KEYS[2], ARGV[2], member)
                    redis.call('HSET', KEYS[3], member, token)
                    table.insert(claimed, member)
                    table.insert(claimed, token)
                end
            end
            return claimed
            """);

    private static final RedisScript<Long> ACK_SCRIPT = longScript("""
            local token = redis.call('HGET', KEYS[2], ARGV[1])
            if not token or token ~= ARGV[2] then
                return 0
            end
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """);

    private static final RedisScript<Long> RETRY_SCRIPT = longScript("""
            local token = redis.call('HGET', KEYS[3], ARGV[1])
            if not token or token ~= ARGV[2] then
                return 0
            end
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            local failures = redis.call('HINCRBY', KEYS[4], ARGV[1], 1)
            if failures >= tonumber(ARGV[5]) then
                redis.call('ZREM', KEYS[1], ARGV[1])
                redis.call('ZADD', KEYS[5], ARGV[4], ARGV[1])
                return -failures
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
            return failures
            """);

    private static final RedisScript<List> RECOVER_SCRIPT = listScript("""
            local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            local retried = 0
            local dead = 0
            for _, member in ipairs(expired) do
                if redis.call('ZREM', KEYS[2], member) == 1 then
                    redis.call('HDEL', KEYS[3], member)
                    local failures = redis.call('HINCRBY', KEYS[4], member, 1)
                    if failures >= tonumber(ARGV[3]) then
                        redis.call('ZREM', KEYS[1], member)
                        redis.call('ZADD', KEYS[5], ARGV[1], member)
                        dead = dead + 1
                    else
                        redis.call('ZADD', KEYS[1], ARGV[2], member)
                        retried = retried + 1
                    end
                end
            end
            return {retried, dead}
            """);

    private static final RedisScript<Long> ENQUEUE_SCRIPT = longScript("""
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            redis.call('HDEL', KEYS[4], ARGV[1])
            redis.call('ZREM', KEYS[5], ARGV[1])
            return 1
            """);

    private static final RedisScript<Long> REMOVE_SCRIPT = longScript("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            removed = removed + redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            redis.call('HDEL', KEYS[4], ARGV[1])
            removed = removed + redis.call('ZREM', KEYS[5], ARGV[1])
            return removed
            """);

    private final StringRedisTemplate redis;
    private final ReminderQueueProperties properties;

    public ReminderScheduleService(StringRedisTemplate redis, ReminderQueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Adds or replaces a task due reminder while preserving the original public API. */
    public void scheduleDueReminder(Long taskId, Instant dueAt) {
        enqueue("DUE:" + requirePositiveId(taskId, "taskId"), dueAt);
    }

    /** Removes a due reminder from ready, processing and dead-letter states atomically. */
    public void removeDueReminder(Long taskId) {
        remove("DUE:" + requirePositiveId(taskId, "taskId"));
    }

    /** Adds or replaces an escalation reminder while preserving the original public API. */
    public void scheduleEscalation(Long reminderId, int attempt, Instant when) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        enqueue("ESC:" + requirePositiveId(reminderId, "reminderId") + ":" + attempt, when);
    }

    /**
     * Atomically moves all due entries from ready to processing and assigns a fenced lease token.
     * Nothing is deleted permanently until {@link #acknowledge(ScheduledEntry)} succeeds.
     */
    public List<ScheduledEntry> claimDue(Instant now) {
        Objects.requireNonNull(now, "now");
        QueueKeys keys = queueKeys();
        long leaseUntil = now.plus(properties.lease()).toEpochMilli();
        List<?> raw = redis.execute(CLAIM_SCRIPT,
                List.of(keys.ready(), keys.processing(), keys.leaseTokens()),
                Long.toString(now.toEpochMilli()), Long.toString(leaseUntil),
                UUID.randomUUID().toString(), Integer.toString(properties.maxBatch()));
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if ((raw.size() & 1) != 0) {
            throw new IllegalStateException("Redis claim script returned an invalid member/token result");
        }
        List<ScheduledEntry> claimed = new ArrayList<>(raw.size() / 2);
        for (int index = 0; index < raw.size(); index += 2) {
            claimed.add(ScheduledEntry.parse(String.valueOf(raw.get(index)),
                    String.valueOf(raw.get(index + 1))));
        }
        return List.copyOf(claimed);
    }

    /** Acknowledges a successful handler only when its lease token is still current. */
    public boolean acknowledge(ScheduledEntry entry) {
        requireLeased(entry);
        QueueKeys keys = queueKeys();
        Long acknowledged = redis.execute(ACK_SCRIPT,
                List.of(keys.processing(), keys.leaseTokens(), keys.failures()),
                entry.member(), entry.leaseToken());
        return acknowledged != null && acknowledged == 1L;
    }

    /**
     * Atomically retries a failed leased entry, or moves it to dead letter at max failures.
     * A stale lease token is fenced out and cannot mutate a newer claim.
     */
    public RetryOutcome retry(ScheduledEntry entry, Instant now) {
        requireLeased(entry);
        Objects.requireNonNull(now, "now");
        QueueKeys keys = queueKeys();
        long retryAt = now.plus(properties.retryDelay()).toEpochMilli();
        Long result = redis.execute(RETRY_SCRIPT, keys.all(),
                entry.member(), entry.leaseToken(), Long.toString(retryAt),
                Long.toString(now.toEpochMilli()), Integer.toString(properties.maxFailures()));
        if (result == null || result == 0L) {
            return RetryOutcome.STALE_LEASE;
        }
        return result < 0L ? RetryOutcome.DEAD_LETTERED : RetryOutcome.RETRIED;
    }

    /** Recovers expired leases and counts lease expiry as a failed delivery attempt. */
    public RecoveryResult recoverExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        QueueKeys keys = queueKeys();
        long retryAt = now.plus(properties.retryDelay()).toEpochMilli();
        List<?> result = redis.execute(RECOVER_SCRIPT, keys.all(),
                Long.toString(now.toEpochMilli()), Long.toString(retryAt),
                Integer.toString(properties.maxFailures()));
        if (result == null || result.size() != 2) {
            return RecoveryResult.NONE;
        }
        return new RecoveryResult(asInt(result.get(0)), asInt(result.get(1)));
    }

    /** Reads the ready schedule time only; a leased entry is intentionally not reported as ready. */
    public Optional<Instant> peekDue(Long taskId) {
        return peekReady("DUE:" + requirePositiveId(taskId, "taskId"));
    }

    /** Reads the ready schedule time only; a leased entry is intentionally not reported as ready. */
    public Optional<Instant> peekEscalation(Long reminderId, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        return peekReady("ESC:" + requirePositiveId(reminderId, "reminderId") + ":" + attempt);
    }

    /** Operational visibility for diagnostics and focused integration tests. */
    public Optional<Instant> deadLetteredAt(ScheduledEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Double score = redis.opsForZSet().score(queueKeys().deadLetter(), entry.member());
        return score == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(score.longValue()));
    }

    /** Operational visibility for diagnostics and focused integration tests. */
    public int failureCount(ScheduledEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Object value = redis.opsForHash().get(queueKeys().failures(), entry.member());
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private void enqueue(String member, Instant when) {
        Objects.requireNonNull(when, "when");
        QueueKeys keys = queueKeys();
        redis.execute(ENQUEUE_SCRIPT, keys.all(), member, Long.toString(when.toEpochMilli()));
    }

    private void remove(String member) {
        QueueKeys keys = queueKeys();
        redis.execute(REMOVE_SCRIPT, keys.all(), member);
    }

    private Optional<Instant> peekReady(String member) {
        Double score = redis.opsForZSet().score(queueKeys().ready(), member);
        return score == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(score.longValue()));
    }

    /** Legacy keeps its deployed ready key; new workspace keys use a Redis Cluster hash tag. */
    static String queueKey() {
        return queueKeys().ready();
    }

    static QueueKeys queueKeys() {
        UUID workspaceId = WorkspaceContextHolder.requireContext().workspaceId();
        String ready = LegacyAccountIds.WORKSPACE_ID.equals(workspaceId)
                ? LEGACY_QUEUE_KEY
                : "reminder:{" + workspaceId + "}:schedule";
        return new QueueKeys(ready, ready + ":processing", ready + ":leases",
                ready + ":failures", ready + ":dead-letter");
    }

    private static long requirePositiveId(Long value, String field) {
        if (value == null || value < 1L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static void requireLeased(ScheduledEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.leaseToken() == null || entry.leaseToken().isBlank()) {
            throw new IllegalArgumentException("entry does not carry a processing lease");
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String source) {
        return new DefaultRedisScript(source, List.class);
    }

    private static RedisScript<Long> longScript(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }

    enum RetryOutcome {
        RETRIED,
        DEAD_LETTERED,
        STALE_LEASE
    }

    public record RecoveryResult(int retried, int deadLettered) {
        static final RecoveryResult NONE = new RecoveryResult(0, 0);

        public RecoveryResult {
            if (retried < 0 || deadLettered < 0) {
                throw new IllegalArgumentException("recovery counts cannot be negative");
            }
        }

        public int total() {
            return retried + deadLettered;
        }
    }

    record QueueKeys(String ready, String processing, String leaseTokens,
                     String failures, String deadLetter) {
        List<String> all() {
            return List.of(ready, processing, leaseTokens, failures, deadLetter);
        }
    }

    /**
     * One business queue entry. Equality intentionally ignores the internal lease token, preserving
     * the original three-field value semantics used by callers and existing tests.
     */
    public static final class ScheduledEntry {
        private final Kind kind;
        private final long id;
        private final int attempt;
        private final String leaseToken;

        public ScheduledEntry(Kind kind, long id, int attempt) {
            this(kind, id, attempt, null);
        }

        private ScheduledEntry(Kind kind, long id, int attempt, String leaseToken) {
            this.kind = Objects.requireNonNull(kind, "kind");
            if (id < 1L) {
                throw new IllegalArgumentException("id must be positive");
            }
            if (attempt < 0 || (kind == Kind.DUE && attempt != 0)
                    || (kind == Kind.ESCALATION && attempt < 1)) {
                throw new IllegalArgumentException("invalid attempt for " + kind);
            }
            this.id = id;
            this.attempt = attempt;
            this.leaseToken = leaseToken;
        }

        public Kind kind() {
            return kind;
        }

        public long id() {
            return id;
        }

        public int attempt() {
            return attempt;
        }

        String leaseToken() {
            return leaseToken;
        }

        String member() {
            return switch (kind) {
                case DUE -> "DUE:" + id;
                case ESCALATION -> "ESC:" + id + ":" + attempt;
            };
        }

        static ScheduledEntry parse(String member) {
            return parse(member, null);
        }

        static ScheduledEntry parse(String member, String leaseToken) {
            Objects.requireNonNull(member, "member");
            String[] parts = member.split(":", -1);
            try {
                if (parts.length == 2 && "DUE".equals(parts[0])) {
                    return new ScheduledEntry(Kind.DUE, Long.parseLong(parts[1]), 0, leaseToken);
                }
                if (parts.length == 3 && "ESC".equals(parts[0])) {
                    return new ScheduledEntry(Kind.ESCALATION, Long.parseLong(parts[1]),
                            Integer.parseInt(parts[2]), leaseToken);
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid schedule entry: " + member, exception);
            }
            throw new IllegalArgumentException("Unknown schedule entry: " + member);
        }

        public enum Kind {
            DUE,
            ESCALATION
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof ScheduledEntry other
                    && kind == other.kind && id == other.id && attempt == other.attempt;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, id, attempt);
        }

        @Override
        public String toString() {
            return "ScheduledEntry[kind=" + kind + ", id=" + id + ", attempt=" + attempt + "]";
        }
    }
}
