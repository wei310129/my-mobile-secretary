package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Objects;
import java.util.UUID;

public record CalendarShareRoleChange(
        String requestKey,
        UUID shareId,
        CalendarSharePermission permission,
        long expectedShareRevision) {

    public CalendarShareRoleChange {
        if (requestKey == null
                || requestKey.isBlank()
                || requestKey.strip().length() > 160) {
            throw new IllegalArgumentException(
                    "A bounded role-change request key is required");
        }
        requestKey = requestKey.strip();
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(permission, "permission");
        if (expectedShareRevision < 1) {
            throw new IllegalArgumentException(
                    "Expected share revision must be positive");
        }
    }
}
