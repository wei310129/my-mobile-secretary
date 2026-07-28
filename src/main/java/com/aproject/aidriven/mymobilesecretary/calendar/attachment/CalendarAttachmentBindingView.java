package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import java.util.UUID;

public record CalendarAttachmentBindingView(
        UUID id,
        long mediaId,
        CalendarAttachmentTarget.TargetKind targetKind,
        UUID planId,
        String displayName,
        int displayOrder,
        Status status,
        long revision) {

    public enum Status {
        ACTIVE,
        REPLACED,
        UNLINKED,
        MEDIA_DELETED
    }
}
