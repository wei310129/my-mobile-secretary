package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.BufferRuleRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 緩衝規則:累積行程結果 → 給規劃引擎「這地點的行程通常會拖多久」。
 *
 * 開發計畫第 14 節第 9 點的 Phase 3 版(手動回報累積);
 * Phase 4 的事件重播與習慣分析會接手自動歸納。
 */
@Service
@Transactional
public class BufferRuleService {

    private static final Logger log = LoggerFactory.getLogger(BufferRuleService.class);

    private final BufferRuleRepository bufferRuleRepository;
    private final BufferRuleProperties properties;
    private final Clock clock;

    public BufferRuleService(BufferRuleRepository bufferRuleRepository,
                             BufferRuleProperties properties,
                             Clock clock) {
        this.bufferRuleRepository = bufferRuleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 行程結果回報 → 累積該地點的統計(無地點行程略過)。與回報同交易,不會少記。 */
    @EventListener
    public void onScheduleOutcomeRecorded(ScheduleOutcomeRecorded event) {
        if (event.placeId() == null) {
            return;
        }
        Instant now = Instant.now(clock);
        BufferRule rule = bufferRuleRepository.findByPlaceId(event.placeId())
                .orElseGet(() -> BufferRule.create(event.placeId(), now));
        rule.recordSample(event.onTime() ? 0 : event.overrunMinutes() == null ? 0 : event.overrunMinutes(), now);
        bufferRuleRepository.save(rule);
        log.info("Buffer rule updated [place={}, samples={}, avgOverrun={}m]",
                event.placeId(), rule.getSampleCount(), rule.averageOverrunMinutes());
    }

    /**
     * 該地點行程的建議緩衝。
     * 樣本不足回 0(不憑巧合加緩衝);平均超時封頂 maxBuffer(單次極端值不擴散)。
     */
    @Transactional(readOnly = true)
    public Duration recommendedBuffer(Long placeId) {
        if (placeId == null) {
            return Duration.ZERO;
        }
        return bufferRuleRepository.findByPlaceId(placeId)
                .filter(rule -> rule.getSampleCount() >= properties.minSamples())
                .map(rule -> {
                    Duration average = Duration.ofMinutes(rule.averageOverrunMinutes());
                    return average.compareTo(properties.maxBuffer()) > 0 ? properties.maxBuffer() : average;
                })
                .orElse(Duration.ZERO);
    }
}
