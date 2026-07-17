package com.aproject.internal.aidispatcher.coordination;

import com.aproject.internal.aidispatcher.codex.CodexLaunchResult;
import com.aproject.internal.aidispatcher.codex.CodexLaunchService;
import com.aproject.internal.aidispatcher.codex.CodexRecoveryResult;
import com.aproject.internal.aidispatcher.codex.CodexRecoveryService;
import com.aproject.internal.aidispatcher.trigger.source.TriggerPollingResult;
import com.aproject.internal.aidispatcher.trigger.source.TriggerPollingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean({CodexLaunchService.class, CodexRecoveryService.class})
public class DispatcherEngine {

    private final DispatcherCoordinator coordinator;
    private final CodexLaunchService launchService;
    private final CodexRecoveryService recoveryService;
    private final TriggerPollingService triggerPollingService;

    public DispatcherEngine(DispatcherCoordinator coordinator,
                            CodexLaunchService launchService,
                            CodexRecoveryService recoveryService,
                            TriggerPollingService triggerPollingService) {
        this.coordinator = coordinator;
        this.launchService = launchService;
        this.recoveryService = recoveryService;
        this.triggerPollingService = triggerPollingService;
    }

    public DispatcherEngineResult tick() {
        CodexRecoveryResult activeRecovery = recoveryService.recover();
        if (activeRecovery.outcome() != CodexRecoveryResult.Outcome.NO_ACTIVE_RUN) {
            triggerPollingService.pollAll();
            return handleRecovery(activeRecovery);
        }

        TriggerPollingResult triggerPolling = triggerPollingService.pollAll();
        if (!triggerPolling.successful()) {
            return DispatcherEngineResult.of(
                    DispatcherEngineResult.Action.TRIGGER_UNAVAILABLE, null);
        }
        DispatcherTickResult coordinated = coordinator.tick();
        return switch (coordinated.outcome()) {
            case IDLE -> DispatcherEngineResult.of(DispatcherEngineResult.Action.IDLE, null);
            case WAITING -> DispatcherEngineResult.of(DispatcherEngineResult.Action.WAITING, null);
            case PAUSED -> DispatcherEngineResult.of(DispatcherEngineResult.Action.PAUSED, null);
            case CLAIMED -> launch(coordinated.runId());
            case BUSY -> recoverActiveRun();
        };
    }

    private DispatcherEngineResult recoverActiveRun() {
        return handleRecovery(recoveryService.recover());
    }

    private DispatcherEngineResult handleRecovery(CodexRecoveryResult recovered) {
        return switch (recovered.outcome()) {
            case LAUNCH_REQUIRED -> launch(recovered.runId());
            case HEALTHY -> DispatcherEngineResult.of(
                    DispatcherEngineResult.Action.ACTIVE, recovered.runId());
            case RECOVERED_RUNNING, COMPLETED -> DispatcherEngineResult.of(
                    DispatcherEngineResult.Action.RECOVERED, recovered.runId());
            case RETRY_PENDING, STALE, NO_ACTIVE_RUN -> DispatcherEngineResult.of(
                    DispatcherEngineResult.Action.RECOVERY_PENDING, recovered.runId());
            case PAUSED -> DispatcherEngineResult.of(
                    DispatcherEngineResult.Action.PAUSED, recovered.runId());
        };
    }

    private DispatcherEngineResult launch(java.util.UUID runId) {
        CodexLaunchResult launched = launchService.launch(runId);
        DispatcherEngineResult.Action action = launched.outcome() == CodexLaunchResult.Outcome.STARTED
                ? DispatcherEngineResult.Action.LAUNCHED
                : DispatcherEngineResult.Action.RECOVERY_PENDING;
        return DispatcherEngineResult.of(action, runId);
    }
}
