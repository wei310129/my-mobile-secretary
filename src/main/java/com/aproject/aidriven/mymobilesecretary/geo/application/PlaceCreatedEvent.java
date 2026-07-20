package com.aproject.aidriven.mymobilesecretary.geo.application;

import java.time.Instant;

/** Published after a place has been validated and persisted. */
public record PlaceCreatedEvent(Long placeId, String name, String type, Instant createdAt) {
}
