package com.aproject.internal.aidispatcher.coordination;

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
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    public DispatcherScheduler(DispatcherEngine engine) {
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        runOneTick();
    }

    @Scheduled(fixedDelayString = "${ai-dispatcher.poll-interval}")
    public void scheduledTick() {
        runOneTick();
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
