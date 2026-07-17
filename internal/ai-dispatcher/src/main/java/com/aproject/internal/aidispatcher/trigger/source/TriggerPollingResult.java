package com.aproject.internal.aidispatcher.trigger.source;

public record TriggerPollingResult(
        boolean successful,
        int sourceCount,
        int pageCount,
        int insertedEventCount,
        String failedSourceKey
) {

    static TriggerPollingResult success(int sourceCount, int pages, int inserted) {
        return new TriggerPollingResult(true, sourceCount, pages, inserted, null);
    }

    static TriggerPollingResult failure(
            int sourceCount, int pages, int inserted, String failedSourceKey) {
        return new TriggerPollingResult(false, sourceCount, pages, inserted, failedSourceKey);
    }
}
