package com.aproject.aidriven.mymobilesecretary.integration.notification;

import java.time.Instant;

public record NotificationDeliveryState(
        Status status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant sentAt,
        Instant terminalAt,
        String lastError) {

    public enum Status {
        NOT_FOUND,
        IN_FLIGHT,
        SENT,
        FAILED
    }

    public static NotificationDeliveryState notFound() {
        return new NotificationDeliveryState(Status.NOT_FOUND, 0, null, null, null, null);
    }

    public static NotificationDeliveryState inFlight(int attemptCount, Instant nextAttemptAt) {
        return new NotificationDeliveryState(
                Status.IN_FLIGHT, attemptCount, nextAttemptAt, null, null, null);
    }

    public static NotificationDeliveryState sent(int attemptCount, Instant sentAt) {
        return new NotificationDeliveryState(
                Status.SENT, attemptCount, null, sentAt, sentAt, null);
    }

    public static NotificationDeliveryState failed(
            int attemptCount, Instant terminalAt, String lastError) {
        return new NotificationDeliveryState(
                Status.FAILED,
                attemptCount,
                null,
                null,
                terminalAt,
                bounded(lastError));
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "DELIVERY_FAILED";
        }
        String stripped = value.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }
}
