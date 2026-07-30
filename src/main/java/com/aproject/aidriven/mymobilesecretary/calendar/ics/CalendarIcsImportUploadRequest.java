package com.aproject.aidriven.mymobilesecretary.calendar.ics;

public record CalendarIcsImportUploadRequest(
        String requestId, String importSource, byte[] content) {

    public CalendarIcsImportUploadRequest {
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }
}
