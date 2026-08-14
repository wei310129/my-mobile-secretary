package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import java.util.Objects;

/**
 * A route reminder is an optional post-creation follow-up, never a prerequisite for route
 * materialization.
 */
public final class CalendarRouteReminderFollowUpPolicy {

    public Decision afterRouteMaterialized(
            CalendarIntentDraftService.DraftView draft, boolean hasExistingDepartureReminder) {
        Objects.requireNonNull(draft, "draft");
        if (!draft.mayClaimCreated() || hasExistingDepartureReminder) {
            return Decision.NO_FOLLOW_UP;
        }
        return Decision.ASK_OPTIONAL_DEPARTURE_REMINDER;
    }

    public enum Decision {
        ASK_OPTIONAL_DEPARTURE_REMINDER,
        NO_FOLLOW_UP
    }
}
