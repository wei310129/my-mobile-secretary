package com.aproject.aidriven.mymobilesecretary.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingPlanTest {

    @Test
    void partialSuccessStopsFurtherExecutionAndPreservesCompletedOrder() {
        var plan = BookingPlan.priced(
                UUID.fromString("00000000-0000-0000-0000-000000000501"), 2);
        var completed = order("00000000-0000-0000-0000-000000000502");

        plan.authorize();
        plan.startExecution();
        plan.recordSuccess("operation-1", completed);
        plan.recordFailure("operation-2", "provider-declined");

        assertThat(plan.state()).isEqualTo(BookingExecutionState.PARTIALLY_COMPLETED);
        assertThat(plan.completedOrders()).containsExactly(completed);
        assertThat(plan.canContinue()).isFalse();
        assertThatThrownBy(() -> plan.recordSuccess("operation-3", order(
                        "00000000-0000-0000-0000-000000000503")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownOutcomeBlocksNewOperationUntilReconciledAndReplayIsIdempotent() {
        var plan = BookingPlan.priced(
                UUID.fromString("00000000-0000-0000-0000-000000000511"), 1);
        var completed = order("00000000-0000-0000-0000-000000000512");

        plan.authorize();
        plan.startExecution();
        plan.recordUnknown("operation-1");
        plan.recordUnknown("operation-1");

        assertThat(plan.state()).isEqualTo(BookingExecutionState.NEEDS_RECONCILIATION);
        assertThat(plan.attempts()).hasSize(1);
        assertThatThrownBy(() -> plan.recordSuccess("operation-2", completed))
                .isInstanceOf(IllegalStateException.class);

        plan.reconcileSuccess("operation-1", completed);
        plan.reconcileSuccess("operation-1", completed);

        assertThat(plan.state()).isEqualTo(BookingExecutionState.COMPLETED);
        assertThat(plan.completedOrders()).containsExactly(completed);
        assertThat(plan.attempts()).hasSize(1);
    }

    @Test
    void enforcesStateMachineOrder() {
        var plan = BookingPlan.priced(
                UUID.fromString("00000000-0000-0000-0000-000000000521"), 1);

        assertThatThrownBy(plan::startExecution).isInstanceOf(IllegalStateException.class);
        plan.authorize();
        assertThat(plan.state()).isEqualTo(BookingExecutionState.AUTHORIZED);
        plan.startExecution();
        assertThat(plan.state()).isEqualTo(BookingExecutionState.EXECUTING);
    }

    private static ExternalBookingOrder order(String id) {
        return new ExternalBookingOrder(
                UUID.fromString(id),
                "fake-air",
                ProviderEnvironment.FAKE,
                "masked-order-ref",
                ExternalBookingOrderStatus.CONFIRMED,
                Instant.parse("2026-07-25T03:01:00Z"));
    }
}
