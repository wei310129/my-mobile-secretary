package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Objects;
import java.util.UUID;

public record CalendarAuthoritativeCapabilityGrant(
        String requestKey,
        UUID shareId,
        Scope scope,
        UUID nodeId,
        long expectedShareRevision) {

    public CalendarAuthoritativeCapabilityGrant {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(scope, "scope");
        if ((scope == Scope.PLAN && nodeId != null)
                || (scope == Scope.NODE && nodeId == null)) {
            throw new IllegalArgumentException(
                    "Authoritative capability scope and node must match");
        }
        if (expectedShareRevision < 1) {
            throw new IllegalArgumentException(
                    "Expected share revision must be positive");
        }
    }

    public enum Scope {
        PLAN,
        NODE
    }
}
