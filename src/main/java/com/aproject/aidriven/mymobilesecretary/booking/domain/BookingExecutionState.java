package com.aproject.aidriven.mymobilesecretary.booking.domain;

public enum BookingExecutionState {
    PRICED,
    AUTHORIZED,
    EXECUTING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    NEEDS_USER_ACTION,
    NEEDS_RECONCILIATION,
    FAILED,
    EXPIRED
}
