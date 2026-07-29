package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.BufferRuleRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BufferRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final UUID ACTOR =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext(
                    ACTOR,
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    WorkspaceChannel.TEST);

    @Mock private BufferRuleRepository repository;
    @Mock private PlaceRepository places;

    private BufferRuleService service() {
        return new BufferRuleService(
                repository,
                places,
                new BufferRuleProperties(3, Duration.ofHours(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BufferRule ruleWithSamples(int... overruns) {
        BufferRule rule = BufferRule.create(7L, NOW);
        for (int overrun : overruns) {
            rule.recordSample(overrun, NOW);
        }
        return rule;
    }

    @Test
    void belowMinSamplesRecommendsZero() {
        when(repository.findByPlaceIdAndCreatedByUserId(7L, ACTOR))
                .thenReturn(Optional.of(ruleWithSamples(30, 30)));

        assertThat(inContext(() -> service().recommendedBuffer(7L))).isEqualTo(Duration.ZERO);
    }

    @Test
    void enoughSamplesRecommendAverage() {
        when(repository.findByPlaceIdAndCreatedByUserId(7L, ACTOR))
                .thenReturn(Optional.of(ruleWithSamples(30, 20, 0)));

        assertThat(inContext(() -> service().recommendedBuffer(7L)))
                .isEqualTo(Duration.ofMinutes(17));
    }

    @Test
    void recommendationIsCappedAtMaxBuffer() {
        when(repository.findByPlaceIdAndCreatedByUserId(7L, ACTOR))
                .thenReturn(Optional.of(ruleWithSamples(300, 300, 300)));

        assertThat(inContext(() -> service().recommendedBuffer(7L)))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void unknownPlaceOrNullRecommendsZero() {
        when(repository.findByPlaceIdAndCreatedByUserId(9L, ACTOR))
                .thenReturn(Optional.empty());

        assertThat(inContext(() -> service().recommendedBuffer(9L))).isEqualTo(Duration.ZERO);
        assertThat(service().recommendedBuffer(null)).isEqualTo(Duration.ZERO);
    }

    @Test
    void outcomeEventAccumulatesSample() {
        Place place = org.mockito.Mockito.mock(Place.class);
        when(place.getCreatedByUserId()).thenReturn(ACTOR);
        when(places.findById(7L)).thenReturn(Optional.of(place));
        when(repository.findForUpdateByPlaceIdAndActorId(7L, ACTOR))
                .thenReturn(Optional.empty());

        inContext(
                () -> {
                    service()
                            .onScheduleOutcomeRecorded(
                                    new ScheduleOutcomeRecorded(1L, 7L, false, 30));
                    return null;
                });

        ArgumentCaptor<BufferRule> captor = ArgumentCaptor.forClass(BufferRule.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSampleCount()).isEqualTo(1);
        assertThat(captor.getValue().getTotalOverrunMinutes()).isEqualTo(30);
    }

    @Test
    void placelessOutcomeIsIgnored() {
        service().onScheduleOutcomeRecorded(new ScheduleOutcomeRecorded(1L, null, true, null));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void explicitPolicyOverridesRecommendationWithoutChangingLearnedCounters() {
        BufferRule rule = ruleWithSamples(30, 20, 0);
        Place place = org.mockito.Mockito.mock(Place.class);
        when(place.getCreatedByUserId()).thenReturn(ACTOR);
        when(places.findById(7L)).thenReturn(Optional.of(place));
        when(repository.findForUpdateByPlaceIdAndActorId(7L, ACTOR))
                .thenReturn(Optional.of(rule));
        when(repository.save(rule)).thenReturn(rule);
        when(repository.findByPlaceIdAndCreatedByUserId(7L, ACTOR))
                .thenReturn(Optional.of(rule));

        BufferRule saved = inContext(() -> service().setExplicitBuffer(7L, 45, 0));

        assertThat(saved.getExplicitBufferMinutes()).isEqualTo(45);
        assertThat(saved.getExplicitRevision()).isEqualTo(1);
        assertThat(saved.getSampleCount()).isEqualTo(3);
        assertThat(saved.getOnTimeCount()).isEqualTo(1);
        assertThat(saved.getTotalOverrunMinutes()).isEqualTo(50);
        assertThat(inContext(() -> service().recommendedBuffer(7L)))
                .isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void explicitPolicyAboveConfiguredMaximumFailsBeforeMutation() {
        assertThatThrownBy(() -> inContext(() -> service().setExplicitBuffer(7L, 121, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        org.mockito.Mockito.verifyNoInteractions(repository, places);
    }

    private static <T> T inContext(Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(CONTEXT)) {
            return work.get();
        }
    }
}
