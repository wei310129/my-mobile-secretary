package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import java.time.Instant;
import java.util.UUID;

public record CalendarReminderRuleView(
        UUID ruleId,
        CalendarReminderOwnerKind ownerKind,
        CalendarReminderDeliveryMode deliveryMode,
        Instant firstScheduledAt) {}
