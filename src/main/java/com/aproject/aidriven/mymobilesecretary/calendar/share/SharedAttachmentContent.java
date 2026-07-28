package com.aproject.aidriven.mymobilesecretary.calendar.share;

public record SharedAttachmentContent(
        String displayName,
        String mediaType,
        boolean image,
        byte[] bytes) {

    public SharedAttachmentContent {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
