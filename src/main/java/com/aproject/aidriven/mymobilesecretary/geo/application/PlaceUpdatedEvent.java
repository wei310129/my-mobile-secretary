package com.aproject.aidriven.mymobilesecretary.geo.application;

import java.time.Instant;

/** Published after a place address and navigation anchor have been safely updated. */
public record PlaceUpdatedEvent(Long placeId, String name, Instant updatedAt) {
}
