package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BookingPlan {

    private final UUID planId;
    private final int totalItems;
    private final Map<String, BookingAttempt> attempts = new LinkedHashMap<>();
    private final Map<UUID, ExternalBookingOrder> completedOrders = new LinkedHashMap<>();
    private BookingExecutionState state = BookingExecutionState.PRICED;

    private BookingPlan(UUID planId, int totalItems) {
        this.planId = Objects.requireNonNull(planId, "planId");
        if (totalItems < 1) {
            throw new IllegalArgumentException("totalItems must be positive");
        }
        this.totalItems = totalItems;
    }

    public static BookingPlan priced(UUID planId, int totalItems) {
        return new BookingPlan(planId, totalItems);
    }

    public void authorize() {
        requireState(BookingExecutionState.PRICED);
        state = BookingExecutionState.AUTHORIZED;
    }

    public void startExecution() {
        requireState(BookingExecutionState.AUTHORIZED);
        state = BookingExecutionState.EXECUTING;
    }

    public void recordSuccess(String operationId, ExternalBookingOrder order) {
        Objects.requireNonNull(order, "order");
        var existing = attempts.get(operationId);
        if (existing != null) {
            if (existing.status() == BookingAttemptStatus.SUCCEEDED
                    && existing.orderId().orElseThrow().equals(order.orderId())) {
                return;
            }
            throw new IllegalStateException("operation replay has a conflicting outcome");
        }
        requireState(BookingExecutionState.EXECUTING);
        attempts.put(operationId, BookingAttempt.succeeded(operationId, order.orderId()));
        completedOrders.putIfAbsent(order.orderId(), order);
        if (completedOrders.size() == totalItems) {
            state = BookingExecutionState.COMPLETED;
        }
    }

    public void recordFailure(String operationId, String publicReason) {
        if (attempts.containsKey(operationId)) {
            return;
        }
        requireState(BookingExecutionState.EXECUTING);
        attempts.put(operationId, BookingAttempt.failed(operationId, publicReason));
        state =
                completedOrders.isEmpty()
                        ? BookingExecutionState.FAILED
                        : BookingExecutionState.PARTIALLY_COMPLETED;
    }

    public void recordUnknown(String operationId) {
        var existing = attempts.get(operationId);
        if (existing != null) {
            if (existing.status() == BookingAttemptStatus.NEEDS_RECONCILIATION) {
                return;
            }
            throw new IllegalStateException("operation replay has a conflicting outcome");
        }
        requireState(BookingExecutionState.EXECUTING);
        attempts.put(operationId, BookingAttempt.unknown(operationId));
        state = BookingExecutionState.NEEDS_RECONCILIATION;
    }

    public void reconcileSuccess(String operationId, ExternalBookingOrder order) {
        Objects.requireNonNull(order, "order");
        var existing = attempts.get(operationId);
        if (existing != null
                && existing.status() == BookingAttemptStatus.SUCCEEDED
                && existing.orderId().orElseThrow().equals(order.orderId())) {
            return;
        }
        if (state != BookingExecutionState.NEEDS_RECONCILIATION
                || existing == null
                || existing.status() != BookingAttemptStatus.NEEDS_RECONCILIATION) {
            throw new IllegalStateException("operation is not awaiting reconciliation");
        }
        attempts.put(operationId, BookingAttempt.succeeded(operationId, order.orderId()));
        completedOrders.putIfAbsent(order.orderId(), order);
        state =
                completedOrders.size() == totalItems
                        ? BookingExecutionState.COMPLETED
                        : BookingExecutionState.EXECUTING;
    }

    public boolean canContinue() {
        return state == BookingExecutionState.EXECUTING;
    }

    public UUID planId() {
        return planId;
    }

    public int totalItems() {
        return totalItems;
    }

    public BookingExecutionState state() {
        return state;
    }

    public List<BookingAttempt> attempts() {
        return List.copyOf(attempts.values());
    }

    public List<ExternalBookingOrder> completedOrders() {
        return new ArrayList<>(completedOrders.values());
    }

    private void requireState(BookingExecutionState expected) {
        if (state != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + state);
        }
    }
}
