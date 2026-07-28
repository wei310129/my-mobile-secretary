package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AvailabilitySearchRequest(Set<UUID> travellerIds) {

    public AvailabilitySearchRequest {
        travellerIds = Set.copyOf(Objects.requireNonNull(travellerIds, "travellerIds"));
        if (travellerIds.isEmpty()) {
            throw new IllegalArgumentException("travellerIds must not be empty");
        }
    }
}
