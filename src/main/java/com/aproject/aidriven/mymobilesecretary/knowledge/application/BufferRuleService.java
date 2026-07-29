package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.BufferRuleRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
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
    private final PlaceRepository placeRepository;
    private final BufferRuleProperties properties;
    private final Clock clock;

    public BufferRuleService(
            BufferRuleRepository bufferRuleRepository,
            PlaceRepository placeRepository,
            BufferRuleProperties properties,
            Clock clock) {
        this.bufferRuleRepository = bufferRuleRepository;
        this.placeRepository = placeRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 行程結果回報 → 累積該地點的統計(無地點行程略過)。與回報同交易,不會少記。 */
    @EventListener
    public void onScheduleOutcomeRecorded(ScheduleOutcomeRecorded event) {
        if (event.placeId() == null) {
            return;
        }
        WorkspaceContext context = tenantContext();
        requireOwnedPlace(event.placeId(), context);
        Instant now = Instant.now(clock);
        BufferRule rule = bufferRuleRepository
                .findForUpdateByPlaceIdAndActorId(event.placeId(), context.actorId())
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
        WorkspaceContext context = tenantContext();
        return bufferRuleRepository
                .findByPlaceIdAndCreatedByUserId(placeId, context.actorId())
                .map(this::effectiveBuffer)
                .orElse(Duration.ZERO);
    }

    /**
     * Saves an explicit actor-confirmed policy while preserving learned outcome counters.
     */
    public BufferRule setExplicitBuffer(Long placeId, int bufferMinutes, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        if (placeId == null || placeId <= 0) {
            throw new IllegalArgumentException("place id must be positive");
        }
        if (bufferMinutes < 0) {
            throw new IllegalArgumentException("explicit buffer must not be negative");
        }
        long maxMinutes = properties.maxBuffer().toMinutes();
        if (bufferMinutes > maxMinutes) {
            throw new IllegalArgumentException("explicit buffer exceeds configured maximum");
        }
        requireOwnedPlace(placeId, context);
        Instant now = Instant.now(clock);
        BufferRule rule =
                bufferRuleRepository
                        .findForUpdateByPlaceIdAndActorId(placeId, context.actorId())
                        .orElseGet(() -> BufferRule.create(placeId, now));
        rule.setExplicitBuffer(bufferMinutes, expectedRevision, now);
        return bufferRuleRepository.save(rule);
    }

    private Duration effectiveBuffer(BufferRule rule) {
        if (rule.getExplicitBufferMinutes() != null) {
            return Duration.ofMinutes(rule.getExplicitBufferMinutes());
        }
        if (rule.getSampleCount() < properties.minSamples()) {
            return Duration.ZERO;
        }
        Duration average = Duration.ofMinutes(rule.averageOverrunMinutes());
        return average.compareTo(properties.maxBuffer()) > 0
                ? properties.maxBuffer()
                : average;
    }

    private void requireOwnedPlace(Long placeId, WorkspaceContext context) {
        placeRepository
                .findById(placeId)
                .filter(place -> context.actorId().equals(place.getCreatedByUserId()))
                .orElseThrow(() -> new NotFoundException("Place", "requested place"));
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Buffer rule requires tenant scope");
        }
        return context;
    }
}
