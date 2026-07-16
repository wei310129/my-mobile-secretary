package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationExitRecorded;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationSender;
import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.FollowUpStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleFollowUp;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcome;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleFollowUpRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleOutcomeRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行程結果追蹤閉環:行程結束後主動問實際結果,回報存成 ScheduleOutcome。
 *
 * 兩條觸發路徑(先到先發,每行程只問一次):
 * 1. 時間路徑:CONFIRMED 行程 endAt 過後,排定 endAt + afterEnd 發問。
 * 2. GPS 路徑:EXIT 事件落在行程地點半徑內,排定 exitAt + afterExit 發問;
 *    已有較晚的排定就提前,已發問則不動。
 *
 * 防騷擾:一天(台北時間)最多 dailyLimit 則;超過的留在 SCHEDULED,隔天再發。
 */
@Service
@Transactional
public class ScheduleFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFollowUpService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    static final String DAILY_COUNT_KEY_PREFIX = "followup:ask:";

    private final ScheduleItemRepository scheduleItemRepository;
    private final ScheduleFollowUpRepository followUpRepository;
    private final ScheduleOutcomeRepository outcomeRepository;
    private final PlaceRepository placeRepository;
    private final List<NotificationSender> senders;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher eventPublisher;
    private final FollowUpProperties properties;
    private final Clock clock;

    public ScheduleFollowUpService(ScheduleItemRepository scheduleItemRepository,
                                   ScheduleFollowUpRepository followUpRepository,
                                   ScheduleOutcomeRepository outcomeRepository,
                                   PlaceRepository placeRepository,
                                   List<NotificationSender> senders,
                                   StringRedisTemplate redis,
                                   ApplicationEventPublisher eventPublisher,
                                   FollowUpProperties properties,
                                   Clock clock) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.followUpRepository = followUpRepository;
        this.outcomeRepository = outcomeRepository;
        this.placeRepository = placeRepository;
        this.senders = senders;
        this.redis = redis;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 時間路徑:為 lookback 窗內已結束、還沒排追蹤的 CONFIRMED 行程排定詢問。
     *
     * @return 這次新排定的筆數(測試與除錯用)
     */
    public int planFollowUpsForEndedSchedules() {
        Instant now = Instant.now(clock);
        List<ScheduleItem> ended = scheduleItemRepository.findByStatusAndEndAtBetween(
                ScheduleStatus.CONFIRMED, now.minus(properties.lookback()), now);
        int planned = 0;
        for (ScheduleItem item : ended) {
            if (followUpRepository.findByScheduleItemId(item.getId()).isPresent()) {
                continue;
            }
            followUpRepository.save(ScheduleFollowUp.planAt(
                    item.getId(), item.getEndAt().plus(properties.afterEnd()), now));
            planned++;
        }
        return planned;
    }

    /**
     * GPS 路徑:離開點落在「進行中/剛結束的行程地點」半徑內 → 視為行程實際結束,
     * 排定(或提前到)exitAt + afterExit 發問。人比行程表早走是常態,不等 endAt。
     */
    @EventListener
    public void onLocationExit(LocationExitRecorded event) {
        List<Long> nearbyPlaceIds = placeRepository
                .findWithinRadius(event.latitude(), event.longitude(), properties.exitRadiusMeters())
                .stream().map(Place::getId).toList();
        if (nearbyPlaceIds.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        Instant candidateDueAt = event.occurredAt().plus(properties.afterExit());
        List<ScheduleItem> candidates = scheduleItemRepository
                .findByStatusAndPlaceIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                        ScheduleStatus.CONFIRMED, nearbyPlaceIds, event.occurredAt(),
                        event.occurredAt().minus(properties.lookback()));
        for (ScheduleItem item : candidates) {
            followUpRepository.findByScheduleItemId(item.getId()).ifPresentOrElse(
                    existing -> existing.advanceIfEarlier(candidateDueAt, now),
                    () -> followUpRepository.save(
                            ScheduleFollowUp.planAt(item.getId(), candidateDueAt, now)));
        }
    }

    /**
     * 發出到期的追蹤詢問。逐筆先過日上限再發:超過上限就整批停住,
     * 留在 SCHEDULED 隔天(台北時間換日)重新計數後再發,不丟失。
     *
     * @return 這次實際發問的筆數(測試與除錯用)
     */
    public int askDueFollowUps() {
        Instant now = Instant.now(clock);
        List<ScheduleFollowUp> due = followUpRepository
                .findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(FollowUpStatus.SCHEDULED, now);
        int asked = 0;
        for (ScheduleFollowUp followUp : due) {
            if (!tryAcquireDailyQuota(now)) {
                log.info("Follow-up daily limit ({}) reached, deferring {} prompts",
                        properties.dailyLimit(), due.size() - asked);
                break;
            }
            ScheduleItem item = scheduleItemRepository.findById(followUp.getScheduleItemId()).orElse(null);
            if (item == null) {
                // 行程被硬刪(理論上不會發生):詢問失去意義,直接作廢
                followUp.markAnswered(now);
                continue;
            }
            sendPrompt(item);
            followUp.markAsked(now);
            asked++;
        }
        return asked;
    }

    /**
     * 使用者回報行程結果:存 outcome、關閉追蹤詢問、行程標記完成。
     * 沒發問也能回報(使用者主動說「剛剛的會準時結束」不該被拒絕)。
     */
    public ScheduleOutcome recordOutcome(Long scheduleItemId, boolean onTime,
                                         Integer overrunMinutes, OutcomeReason reason, String note) {
        ScheduleItem item = scheduleItemRepository.findById(scheduleItemId)
                .orElseThrow(() -> new NotFoundException("Schedule", scheduleItemId));
        if (outcomeRepository.existsByScheduleItemId(scheduleItemId)) {
            throw new BusinessException(
                    "OUTCOME_ALREADY_RECORDED", "Schedule %d outcome already recorded".formatted(scheduleItemId));
        }

        Instant now = Instant.now(clock);
        ScheduleOutcome outcome = onTime
                ? ScheduleOutcome.onTime(scheduleItemId, note, now)
                : ScheduleOutcome.overrun(scheduleItemId, overrunMinutes == null ? 0 : overrunMinutes,
                        reason, note, now);
        outcomeRepository.save(outcome);

        // 回報就是「行程真的結束了」:還在 CONFIRMED 的順手標完成;已完成/取消的不動
        if (item.getStatus() == ScheduleStatus.CONFIRMED) {
            item.complete(now);
        }
        followUpRepository.findByScheduleItemId(scheduleItemId)
                .filter(f -> f.getStatus() != FollowUpStatus.ANSWERED)
                .ifPresent(f -> f.markAnswered(now));

        // 廣播給 knowledge 累積地點緩衝統計(同交易,回報與統計一致)
        eventPublisher.publishEvent(new ScheduleOutcomeRecorded(
                scheduleItemId, item.getPlaceId(), outcome.isOnTime(), outcome.getOverrunMinutes()));
        return outcome;
    }

    /**
     * 自然語言回報(LINE/意圖):使用者說「準時結束」時對到最近一次發問的行程。
     *
     * @return empty 表示目前沒有等待回報的詢問,呼叫端應回問是哪個行程
     */
    public Optional<OutcomeRecorded> recordOutcomeForLatestAsked(boolean onTime, Integer overrunMinutes,
                                                                 OutcomeReason reason, String note) {
        return followUpRepository.findFirstByStatusOrderByAskedAtDescIdDesc(FollowUpStatus.ASKED)
                .map(followUp -> {
                    ScheduleOutcome outcome = recordOutcome(
                            followUp.getScheduleItemId(), onTime, overrunMinutes, reason, note);
                    ScheduleItem item = scheduleItemRepository
                            .findById(followUp.getScheduleItemId()).orElseThrow();
                    return new OutcomeRecorded(item, outcome);
                });
    }

    /** 查行程結果;行程不存在或尚未回報 → 404。 */
    @Transactional(readOnly = true)
    public ScheduleOutcome getOutcome(Long scheduleItemId) {
        if (!scheduleItemRepository.existsById(scheduleItemId)) {
            throw new NotFoundException("Schedule", scheduleItemId);
        }
        return outcomeRepository.findByScheduleItemId(scheduleItemId)
                .orElseThrow(() -> new NotFoundException("ScheduleOutcome", scheduleItemId));
    }

    /**
     * 日上限計數:Redis INCR 台北日期 key。先取號再發,超號不退
     * (寧可少發一則,不可能因通知失敗重試而爆量)。
     */
    private boolean tryAcquireDailyQuota(Instant now) {
        String key = DAILY_COUNT_KEY_PREFIX + LocalDate.ofInstant(now, TAIPEI);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 第一則設過期:留 48 小時方便跨日排查,之後自動清掉
            redis.expire(key, Duration.ofHours(48));
        }
        return count != null && count <= properties.dailyLimit();
    }

    /** 逐通道送出詢問;單一通道失敗只記錄(詢問屬 nice-to-have,不建 delivery 紀錄)。 */
    private void sendPrompt(ScheduleItem item) {
        String message = "「%s」結束了嗎?\n\n請回覆:\n準時或超時\n超時多久\n原因（例如會議超時、交通意外、上下班尖峰）"
                .formatted(item.getTitle());
        for (NotificationSender sender : senders) {
            try {
                sender.send(new ReminderNotification(null, null, "行程結果回報", message));
            } catch (Exception e) {
                log.warn("Follow-up prompt delivery failed [schedule={}]", item.getId(), e);
            }
        }
    }

    /** 自然語言回報的結果:行程 + 已存的 outcome(表達層組回覆用)。 */
    public record OutcomeRecorded(ScheduleItem item, ScheduleOutcome outcome) {
    }
}
