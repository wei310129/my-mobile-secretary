package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record CalendarRegistrationPolicyChange(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        Instant opensAt,
        Instant closesAt,
        String zoneId,
        Integer capacity,
        CalendarLateJoinPolicy lateJoinPolicy,
        boolean waitlistEnabled,
        CalendarWaitlistPromotionMode promotionMode,
        CalendarRegistrationLimitMode limitMode,
        Duration offerTtl,
        CalendarLateNotificationPolicy lateNotificationPolicy,
        boolean participantRosterVisible,
        long expectedRevision) {}
