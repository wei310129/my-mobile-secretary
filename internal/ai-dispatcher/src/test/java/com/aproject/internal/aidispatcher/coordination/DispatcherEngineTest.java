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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DispatcherEngineTest {

    private final DispatcherCoordinator coordinator = mock(DispatcherCoordinator.class);
    private final CodexLaunchService launchService = mock(CodexLaunchService.class);
    private final CodexRecoveryService recoveryService = mock(CodexRecoveryService.class);
    private final DispatcherEngine engine =
            new DispatcherEngine(coordinator, launchService, recoveryService);

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
        verify(recoveryService, never()).recover();
    }

    @Test
    void startupRecoveryLaunchesACommittedUndispatchedRun() {
        UUID runId = UUID.randomUUID();
        when(coordinator.tick()).thenReturn(new DispatcherTickResult(
                DispatcherTickResult.Outcome.BUSY,
                DispatcherState.STARTING, null, 0, null));
        when(recoveryService.recover()).thenReturn(new CodexRecoveryResult(
                CodexRecoveryResult.Outcome.LAUNCH_REQUIRED, runId, 0));
        when(launchService.launch(runId)).thenReturn(
                new CodexLaunchResult(CodexLaunchResult.Outcome.STARTED, runId, "execution-1"));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action()).isEqualTo(DispatcherEngineResult.Action.LAUNCHED);
        verify(launchService).launch(runId);
    }

    @Test
    void waitingDoesNotTouchTheCodexPortServices() {
        when(coordinator.tick()).thenReturn(new DispatcherTickResult(
                DispatcherTickResult.Outcome.WAITING,
                DispatcherState.WAITING, null, 3, null));

        DispatcherEngineResult result = engine.tick();

        assertThat(result.action()).isEqualTo(DispatcherEngineResult.Action.WAITING);
        verify(launchService, never()).launch(org.mockito.ArgumentMatchers.any());
        verify(recoveryService, never()).recover();
    }
}
