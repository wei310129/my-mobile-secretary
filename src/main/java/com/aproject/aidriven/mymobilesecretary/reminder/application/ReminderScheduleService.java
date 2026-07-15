package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 延遲提醒佇列:sorted set,score = 到期時間(epoch millis),member = 排程項目。
 *
 * member 編碼:
 * - "DUE:{taskId}"                 任務到期提醒
 * - "ESC:{reminderId}:{attempt}"   第 attempt 次升級催促
 *
 * 同 member 重複 ZADD 只會更新 score(同任務的到期提醒天然去重)。
 * 關鍵規則:這裡只管佇列進出,不做任何業務判斷——該不該提醒由觸發/升級服務決定。
 */
@Service
public class ReminderScheduleService {

    static final String QUEUE_KEY = "reminder:schedule";

    private final StringRedisTemplate redis;

    public ReminderScheduleService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 排入任務到期提醒;同任務重複排入只更新時間。 */
    public void scheduleDueReminder(Long taskId, Instant dueAt) {
        redis.opsForZSet().add(QUEUE_KEY, "DUE:" + taskId, dueAt.toEpochMilli());
    }

    /** 移除任務的到期提醒排程(任務改成沒有期限時)。 */
    public void removeDueReminder(Long taskId) {
        redis.opsForZSet().remove(QUEUE_KEY, "DUE:" + taskId);
    }

    /** 排入第 attempt 次升級催促。 */
    public void scheduleEscalation(Long reminderId, int attempt, Instant when) {
        redis.opsForZSet().add(QUEUE_KEY, "ESC:" + reminderId + ":" + attempt, when.toEpochMilli());
    }

    /**
     * 撈出並移除所有已到期的排程項目(claim)。
     * ZREM 回傳值確保每個項目只被一個處理者取得(單實例下即防止重複處理)。
     */
    public List<ScheduledEntry> claimDue(Instant now) {
        Set<String> due = redis.opsForZSet().rangeByScore(QUEUE_KEY, 0, now.toEpochMilli());
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        List<ScheduledEntry> claimed = new ArrayList<>();
        for (String member : due) {
            Long removed = redis.opsForZSet().remove(QUEUE_KEY, member);
            if (removed != null && removed > 0) {
                claimed.add(ScheduledEntry.parse(member));
            }
        }
        return claimed;
    }

    /** 查任務到期提醒的排定時間(測試與除錯用,不移除)。 */
    public Optional<Instant> peekDue(Long taskId) {
        return peek("DUE:" + taskId);
    }

    /** 查升級催促的排定時間(測試與除錯用,不移除)。 */
    public Optional<Instant> peekEscalation(Long reminderId, int attempt) {
        return peek("ESC:" + reminderId + ":" + attempt);
    }

    private Optional<Instant> peek(String member) {
        Double score = redis.opsForZSet().score(QUEUE_KEY, member);
        return score == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(score.longValue()));
    }

    /**
     * 佇列中的一筆排程。
     *
     * @param id      DUE → taskId;ESCALATION → reminderId
     * @param attempt 升級次數;DUE 恆為 0
     */
    public record ScheduledEntry(Kind kind, long id, int attempt) {

        public enum Kind {
            DUE,
            ESCALATION
        }

        /** 解析 member 字串;格式不符丟 IllegalArgumentException(由 worker 捕捉記錄)。 */
        static ScheduledEntry parse(String member) {
            String[] parts = member.split(":");
            return switch (parts[0]) {
                case "DUE" -> new ScheduledEntry(Kind.DUE, Long.parseLong(parts[1]), 0);
                case "ESC" -> new ScheduledEntry(Kind.ESCALATION, Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
                default -> throw new IllegalArgumentException("Unknown schedule entry: " + member);
            };
        }
    }
}
