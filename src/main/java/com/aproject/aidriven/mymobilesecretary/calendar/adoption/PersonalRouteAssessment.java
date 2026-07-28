package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.time.Duration;

public record PersonalRouteAssessment(
        PersonalRouteStatus status,
        String fromNodeKey,
        String toNodeKey,
        Duration requiredTravel,
        Duration availableGap) {}
