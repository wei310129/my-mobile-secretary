package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.calendar-v2")
public record CalendarV2RoutingProperties(
        boolean cutoverEnabled, Set<UUID> pilotActorIds) {

    public CalendarV2RoutingProperties {
        pilotActorIds = Set.copyOf(pilotActorIds == null ? Set.of() : pilotActorIds);
    }
}
