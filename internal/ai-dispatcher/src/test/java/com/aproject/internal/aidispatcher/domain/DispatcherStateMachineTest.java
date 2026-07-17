package com.aproject.internal.aidispatcher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class DispatcherStateMachineTest {

    private final DispatcherStateMachine stateMachine = new DispatcherStateMachine();

    @Test
    void supportsTheNormalLifecycle() {
        DispatcherState state = DispatcherState.IDLE;
        state = stateMachine.transition(state, DispatcherState.WAITING);
        state = stateMachine.transition(state, DispatcherState.STARTING);
        state = stateMachine.transition(state, DispatcherState.RUNNING);
        state = stateMachine.transition(state, DispatcherState.WAITING);

        assertThat(state).isEqualTo(DispatcherState.WAITING);
    }

    @Test
    void supportsFailClosedRecovery() {
        DispatcherState state = stateMachine.transition(
                DispatcherState.RUNNING, DispatcherState.RECOVERING);
        state = stateMachine.transition(state, DispatcherState.PAUSED);

        assertThat(state).isEqualTo(DispatcherState.PAUSED);
    }

    @Test
    void rejectsStartingWhileAlreadyRunning() {
        assertThatIllegalStateException().isThrownBy(() -> stateMachine.transition(
                DispatcherState.RUNNING, DispatcherState.STARTING));
    }

    @Test
    void rejectsSkippingTheDurableStartingState() {
        assertThatIllegalStateException().isThrownBy(() -> stateMachine.transition(
                DispatcherState.WAITING, DispatcherState.RUNNING));
    }

    @Test
    void repeatedStateIsAnIdempotentNoOp() {
        assertThat(stateMachine.transition(DispatcherState.RUNNING, DispatcherState.RUNNING))
                .isEqualTo(DispatcherState.RUNNING);
    }
}
