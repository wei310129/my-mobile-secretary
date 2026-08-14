package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.RouteJourneyKind;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRouteReminderFollowUpPolicyTest {

    private final CalendarRouteReminderFollowUpPolicy policy = new CalendarRouteReminderFollowUpPolicy();

    @Test
    void reminderQuestionNeverBlocksAnUnmaterializedRoute() {
        assertThat(policy.afterRouteMaterialized(pendingDraft(), false))
                .isEqualTo(CalendarRouteReminderFollowUpPolicy.Decision.NO_FOLLOW_UP);
    }

    @Test
    void createdRouteGetsOneOptionalReminderFollowUp() {
        assertThat(policy.afterRouteMaterialized(materializedDraft(), false))
                .isEqualTo(CalendarRouteReminderFollowUpPolicy.Decision.ASK_OPTIONAL_DEPARTURE_REMINDER);
        assertThat(policy.afterRouteMaterialized(materializedDraft(), true))
                .isEqualTo(CalendarRouteReminderFollowUpPolicy.Decision.NO_FOLLOW_UP);
    }

    private static CalendarIntentDraftService.DraftView pendingDraft() {
        return draft(CalendarIntentDraftService.Status.PENDING, null);
    }

    private static CalendarIntentDraftService.DraftView materializedDraft() {
        return draft(CalendarIntentDraftService.Status.MATERIALIZED, UUID.randomUUID());
    }

    private static CalendarIntentDraftService.DraftView draft(
            CalendarIntentDraftService.Status status, UUID planId) {
        Instant start = Instant.parse("2026-08-13T01:00:00Z");
        CalendarLocation point = new CalendarLocation("地點", 25.0, 121.0);
        return new CalendarIntentDraftService.DraftView(
                UUID.randomUUID(),
                "前往地點",
                CalendarPlacement.interval(start, start.plusSeconds(900), ZoneId.of("Asia/Taipei")),
                null,
                point,
                point,
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.RouteProviderStatus.AVAILABLE,
                CalendarIntentDraftService.RouteTimeRole.DEPART_AT,
                CalendarIntentDraftService.RouteProvider.TDX,
                start,
                RouteJourneyKind.STANDALONE_TRIP,
                status,
                3,
                planId,
                start.plusSeconds(3600));
    }
}
