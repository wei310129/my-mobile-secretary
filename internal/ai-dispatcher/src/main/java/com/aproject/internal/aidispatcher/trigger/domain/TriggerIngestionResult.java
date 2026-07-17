package com.aproject.internal.aidispatcher.trigger.domain;

public record TriggerIngestionResult(
        Status status,
        int receivedCount,
        int insertedCount,
        String currentCursor
) {

    public static TriggerIngestionResult applied(int received, int inserted, String currentCursor) {
        return new TriggerIngestionResult(Status.APPLIED, received, inserted, currentCursor);
    }

    public static TriggerIngestionResult stale(int received, String currentCursor) {
        return new TriggerIngestionResult(Status.STALE_CURSOR, received, 0, currentCursor);
    }

    public enum Status {
        APPLIED,
        STALE_CURSOR
    }
}
