package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ScheduleClarificationDraftTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    private static final ConversationScopeKey SCOPE = new ConversationScopeKey("a".repeat(64), 1);

    @Test
    void explicitCorrectionOverwritesOnlySuppliedRecurrenceSlots() {
        ScheduleClarificationDraft draft = create(ScheduleClarificationCapability.CONDITIONAL_RECURRENCE);
        draft.mergeRecurrence("課程", DayOfWeek.WEDNESDAY, LocalTime.of(19, 0), true,
                60, LocalDate.of(2026, 12, 31), true, "SKIP", "NEXT_BUSINESS_DAY",
                "TAIPEI", NOW.plusSeconds(1));
        long revision = draft.getRevision();

        draft.mergeRecurrence(null, DayOfWeek.THURSDAY, LocalTime.of(20, 0), true,
                null, null, false, null, null, null, NOW.plusSeconds(2));

        assertThat(draft.getWeekday()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(draft.getStartTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(draft.getTitle()).isEqualTo("課程");
        assertThat(draft.getDurationMinutes()).isEqualTo(60);
        assertThat(draft.getHolidayPolicy()).isEqualTo("SKIP");
        assertThat(draft.getRevision()).isEqualTo(revision + 1);
    }

    @Test
    void monthlyAndVenueAbsorbMultipleTypedValuesWithoutErasingKnownSlots() {
        ScheduleClarificationDraft monthly = create(ScheduleClarificationCapability.MONTHLY_ORDINAL);
        monthly.mergeMonthly(1, 1, DayOfWeek.MONDAY, LocalTime.of(9, 0), true,
                null, "月會", NOW.plusSeconds(1));
        monthly.mergeMonthly(null, null, null, null, false,
                60, null, NOW.plusSeconds(2));
        assertThat(monthly.getOrdinalValue()).isEqualTo(1);
        assertThat(monthly.getMonthOffset()).isEqualTo(1);
        assertThat(monthly.getDurationMinutes()).isEqualTo(60);
        assertThat(monthly.getTitle()).isEqualTo("月會");

        ScheduleClarificationDraft venue = create(ScheduleClarificationCapability.CONDITIONAL_VENUE);
        venue.mergeVenue(NOW.plusSeconds(7200), null, "活動", "甲館", "乙館",
                null, false, NOW.plusSeconds(1));
        venue.mergeVenue(null, 90, null, null, null, NOW.plusSeconds(3600),
                true, NOW.plusSeconds(2));
        assertThat(venue.getPrimaryPlace()).isEqualTo("甲館");
        assertThat(venue.getFallbackPlace()).isEqualTo("乙館");
        assertThat(venue.getDurationMinutes()).isEqualTo(90);
        assertThat(venue.isDecisionPeriodExplicit()).isTrue();
    }

    @Test
    void cancelAndExpiryUseInjectedInstantAndRejectFurtherMutation() {
        ScheduleClarificationDraft canceled = create(ScheduleClarificationCapability.MONTHLY_ORDINAL);
        canceled.cancel(NOW.plusSeconds(1));
        assertThat(canceled.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.CANCELED);
        assertThatThrownBy(() -> canceled.mergeMonthly(1, 0, DayOfWeek.MONDAY,
                LocalTime.NOON, true, 60, "月會", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);

        ScheduleClarificationDraft expired = create(ScheduleClarificationCapability.CONDITIONAL_VENUE);
        assertThat(expired.expireIfDue(NOW.plusSeconds(599))).isFalse();
        assertThat(expired.expireIfDue(NOW.plusSeconds(600))).isTrue();
        assertThat(expired.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.EXPIRED);
    }

    private static ScheduleClarificationDraft create(ScheduleClarificationCapability capability) {
        return ScheduleClarificationDraft.create(
                SCOPE, WorkspaceChannel.TEST, capability, NOW.plusSeconds(600), NOW);
    }
}
