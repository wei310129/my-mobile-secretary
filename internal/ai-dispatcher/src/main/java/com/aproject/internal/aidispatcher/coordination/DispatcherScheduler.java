package com.aproject.internal.aidispatcher.coordination;

import com.aproject.internal.aidispatcher.retention.DispatcherDataRetentionService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-dispatcher", name = "enabled", havingValue = "true")
public class DispatcherScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatcherScheduler.class);

    private final DispatcherEngine engine;
    private final DispatcherDataRetentionService retentionService;
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    public DispatcherScheduler(DispatcherEngine engine,
                               DispatcherDataRetentionService retentionService) {
        this.engine = engine;
        this.retentionService = retentionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        runOneTick();
    }

    @Scheduled(fixedDelayString = "${ai-dispatcher.poll-interval}")
    public void scheduledTick() {
        runOneTick();
    }

    @Scheduled(fixedDelayString = "${ai-dispatcher.retention.sweep-interval}")
    public void retentionSweep() {
        try {
            int purged = retentionService.purgeExpiredConsumedPayloads();
            LOGGER.debug("Dispatcher payload retention purged {} events", purged);
        } catch (RuntimeException failure) {
            LOGGER.error("Dispatcher payload retention sweep failed", failure);
        }
    }

    void runOneTick() {
        if (!tickInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Skipping overlapping dispatcher tick");
            return;
        }
        try {
            DispatcherEngineResult result = engine.tick();
            LOGGER.debug("Dispatcher tick completed with action {}", result.action());
        } catch (RuntimeException failure) {
            LOGGER.error("Dispatcher tick failed; durable state will be reconciled on a later tick", failure);
        } finally {
            tickInProgress.set(false);
        }
    }
}
