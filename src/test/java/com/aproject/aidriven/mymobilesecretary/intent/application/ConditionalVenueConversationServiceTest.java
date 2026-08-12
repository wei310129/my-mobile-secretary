package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.decision.application.ConditionalVenueService;
import com.aproject.aidriven.mymobilesecretary.schedule.decision.domain.ConditionalVenueDraft;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConditionalVenueConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));

    @Mock private ConditionalVenueService venues;
    @Mock private ConditionalVenueDraft draft;

    @Test
    void incompleteRequestAsksOneTypedQuestionWithoutMutation() {
        when(venues.latestPending()).thenReturn(Optional.empty());
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "如果甲館休館就改去乙館活動，我只要一個行程，場地之後決定",
                null, mutations::incrementAndGet).orElseThrow();

        assertThat(result.nextQuestion().code()).isEqualTo("conditional-venue.event-at");
        assertThat(result.message())
                .contains("活動是哪一天幾點開始")
                .doesNotContain("持續多久", "什麼時候提醒", "活動要叫什麼");
        assertThat(mutations).hasValue(0);
    }

    @Test
    void mentioningOneCandidateToAskAQuestionDoesNotSelectIt() {
        AtomicInteger mutations = new AtomicInteger();
        when(venues.latestPending()).thenReturn(Optional.of(draft));
        when(draft.getPrimaryPlaceName()).thenReturn("甲館");
        when(draft.getFallbackPlaceName()).thenReturn("乙館");

        Optional<IntentResult> result = service().answer(
                "乙館的停車場怎麼走？", null, mutations::incrementAndGet);

        assertThat(result).isEmpty();
        assertThat(mutations).hasValue(0);
        verify(venues, never()).resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void explicitActivityNameFlowsIntoTheDraftInsteadOfAHardCodedTitle() {
        when(venues.latestPending()).thenReturn(Optional.empty());
        when(venues.createDraft(
                "董事會",
                Instant.parse("2026-07-19T12:00:00Z"),
                Duration.ofHours(1),
                "甲館",
                "乙館",
                Instant.parse("2026-07-19T10:00:00Z")))
                .thenReturn(draft);
        when(draft.getId()).thenReturn(7L);
        when(draft.getEventStartAt()).thenReturn(Instant.parse("2026-07-19T12:00:00Z"));
        when(draft.getPrimaryPlaceName()).thenReturn("甲館");
        when(draft.getFallbackPlaceName()).thenReturn("乙館");
        when(draft.getDecisionAt()).thenReturn(Instant.parse("2026-07-19T10:00:00Z"));

        IntentResult result = service().answer(
                "明天晚上八點去甲館，如果休館就改去乙館活動，我只要一個行程，"
                        + "場地明天下午六點決定，活動名稱是董事會，每次董事會一小時",
                null,
                () -> { })
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
        verify(venues).createDraft(
                "董事會",
                Instant.parse("2026-07-19T12:00:00Z"),
                Duration.ofHours(1),
                "甲館",
                "乙館",
                Instant.parse("2026-07-19T10:00:00Z"));
    }

    private ConditionalVenueConversationService service() {
        return new ConditionalVenueConversationService(venues, CLOCK);
    }
}
