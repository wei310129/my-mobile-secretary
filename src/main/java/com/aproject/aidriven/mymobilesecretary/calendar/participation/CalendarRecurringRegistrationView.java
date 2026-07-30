package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarOccurrenceKey;
import java.util.UUID;

public record CalendarRecurringRegistrationView(
        UUID id,
        CalendarRecurringRegistrationScope registrationScope,
        CalendarRegistrationState state,
        CalendarOccurrenceKey occurrenceKey,
        long revision) {}
