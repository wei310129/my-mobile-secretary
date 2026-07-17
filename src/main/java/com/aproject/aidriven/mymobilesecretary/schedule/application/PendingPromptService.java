package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.TenantRedisKeys;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pending 空閒詢問:使用者有空檔時,主動問要不要安排 pending 事項。
 *
 * 四道放行條件(全過才問):
 * 1. pending 池非空。
 * 2. 空閒:未來 idleWindow 內沒有已確認行程。
 * 3. 非安靜時段(預設只在 08:00-21:00 台北時間詢問)。
 * 4. minInterval 內沒問過(Redis SETNX + TTL 天然去重)。
 */
@Service
@Transactional(readOnly = true)
public class PendingPromptService {

    private static final Logger log = LoggerFactory.getLogger(PendingPromptService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    static final String PROMPT_GUARD_KEY = "pending:prompt:last";

    private final ScheduleItemRepository scheduleItemRepository;
    private final NotificationPublisher notificationPublisher;
    private final StringRedisTemplate redis;
    private final PendingPromptProperties properties;
    private final Clock clock;

    public PendingPromptService(ScheduleItemRepository scheduleItemRepository,
                                 NotificationPublisher notificationPublisher,
                                StringRedisTemplate redis,
                                PendingPromptProperties properties,
                                Clock clock) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.notificationPublisher = notificationPublisher;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 檢查條件並在全過時發出詢問。
     *
     * @return true 表示這次真的問了(測試與除錯用)
     */
    @Transactional
    public boolean promptIfIdle() {
        List<ScheduleItem> pending = scheduleItemRepository
                .findByStatusOrderByStartAtAsc(ScheduleStatus.PENDING);
        if (pending.isEmpty()) {
            return false;
        }

        Instant now = Instant.now(clock);

        // 安靜時段不吵人
        LocalTime localNow = LocalTime.ofInstant(now, TAIPEI);
        if (localNow.isBefore(properties.start()) || !localNow.isBefore(properties.end())) {
            return false;
        }

        // 未來 idleWindow 內有已確認行程 → 不算空閒
        boolean busy = scheduleItemRepository.existsByStatusAndStartAtLessThanAndEndAtGreaterThan(
                ScheduleStatus.CONFIRMED, now.plus(properties.idleWindow()), now);
        if (busy) {
            return false;
        }

        // SETNX + TTL:minInterval 內只問一次(key 還在就代表問過)
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(TenantRedisKeys.current(PROMPT_GUARD_KEY),
                        now.toString(), properties.minInterval());
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }

        String titles = pending.stream()
                .limit(5)
                .map(item -> "「" + item.getTitle() + "」")
                .collect(Collectors.joining("\n"));
        String message = "你現在有空檔。\n\n待安排事項（%d 件）:\n%s\n\n要排進行程嗎?"
                .formatted(pending.size(), titles);

        notificationPublisher.enqueue(new NotificationRequest(
                null, "pending-prompt:" + now, null, null, "待安排事項", message));
        log.info("Pending prompt queued [items={}]", pending.size());
        return true;
    }
}
