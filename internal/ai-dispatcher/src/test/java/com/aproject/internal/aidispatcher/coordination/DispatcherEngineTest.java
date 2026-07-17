package com.aproject.internal.aidispatcher.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.internal.aidispatcher.codex.CodexLaunchResult;
import com.aproject.internal.aidispatcher.codex.CodexLaunchService;
import com.aproject.internal.aidispatcher.codex.CodexRecoveryResult;
import com.aproject.internal.aidispatcher.codex.CodexRecoveryService;
import com.aproject.internal.aidispatcher.domain.DispatcherState;
import com.aproject.internal.aidispatcher.trigger.source.TriggerPollingResult;
import com.aproject.internal.aidispatcher.trigger.source.TriggerPollingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatcherEngineTest {

    private final DispatcherCoordinator coordinator = mock(DispatcherCoordinator.class);
    private final CodexLaunchService launchService = mock(CodexLaunchService.class);
    private final CodexRecoveryService recoveryService = mock(CodexRecoveryService.class);
    private final TriggerPollingService triggerPollingService = mock(TriggerPollingService.class);
    private final DispatcherEngine engine =
            new DispatcherEngine(coordinator, launchService, recoveryService, triggerPollingService);

    @BeforeEach
    void noActiveRunAndSuccessfulTriggerPolling() {
        when(recoveryService.recover()).thenReturn(new CodexRecoveryResult(
                CodexRecoveryResult.Outcome.NO_ACTIVE_RUN, null, 0));
        when(triggerPollingService.pollAll()).thenReturn(
                new TriggerPollingResult(true, 1, 1, 0, null));
    }

    @Test
    void launchesAJustClaimedRun() {
        UUID runId = UUID.randomUUID();
        when(coordinator.tick()).thenReturn(new DispatcherTickResult(
                DispatcherTickResult.Outcome.CLAIMED,
                DispatcherState.STARTING, runId, 10, null));
        when(launchService.launch(runId)).thenReturn(
                new CodexLaunchResult(CodexLaunchResult.Outcome.STARTED, runId, "execution-1"));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action()).isEqualTo(DispatcherEngineResult.Action.LAUNCHED);
        verify(launchService).launch(runId);
        verify(recoveryService).recover();
    }

    @Test
    void startupRecoveryLaunchesACommittedUndispatchedRun() {
        UUID runId = UUID.randomUUID();
        when(recoveryService.recover()).thenReturn(new CodexRecoveryResult(
                CodexRecoveryResult.Outcome.LAUNCH_REQUIRED, runId, 0));
        when(launchService.launch(runId)).thenReturn(
                new CodexLaunchResult(CodexLaunchResult.Outcome.STARTED, runId, "execution-1"));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action()).isEqualTo(DispatcherEngineResult.Action.LAUNCHED);
        verify(launchService).launch(runId);
    }

    @Test
    void feedFailurePreventsAnewRunFromBeingClaimed() {
        when(triggerPollingService.pollAll()).thenReturn(
                new TriggerPollingResult(false, 1, 0, 0, "main-conversation-feed-v1"));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action())
                .isEqualTo(DispatcherEngineResult.Action.TRIGGER_UNAVAILABLE);
        verify(coordinator, never()).tick();
        verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void waitingDoesNotTouchTheCodexPortServices() {
        when(coordinator.tick()).thenReturn(new DispatcherTickResult(
                DispatcherTickResult.Outcome.WAITING,
                DispatcherState.WAITING, null, 3, null));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action()).isEqualTo(DispatcherEngineResult.Action.WAITING);
        verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
        verify(recoveryService).recover();
    }
}
