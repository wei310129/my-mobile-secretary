package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.BufferRuleRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcomeRecorded;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 緩衝建議規則測試:樣本門檻、上限封頂、無地點略過。
 */
@ExtendWith(MockitoExtension.class)
class BufferRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");

    @Mock
    private BufferRuleRepository repository;

    private BufferRuleService service() {
        return new BufferRuleService(repository,
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

    /** 樣本不足門檻(3)→ 不建議緩衝,巧合不是習慣。 */
    @Test
    void belowMinSamplesRecommendsZero() {
        when(repository.findByPlaceId(7L)).thenReturn(Optional.of(ruleWithSamples(30, 30)));

        assertThat(service().recommendedBuffer(7L)).isEqualTo(Duration.ZERO);
    }

    @Test
    void enoughSamplesRecommendAverage() {
        when(repository.findByPlaceId(7L)).thenReturn(Optional.of(ruleWithSamples(30, 20, 0)));

        assertThat(service().recommendedBuffer(7L)).isEqualTo(Duration.ofMinutes(17));
    }

    /** 極端超時(每次 5 小時)→ 封頂 2 小時,不讓單一地點吃掉整天行程。 */
    @Test
    void recommendationIsCappedAtMaxBuffer() {
        when(repository.findByPlaceId(7L)).thenReturn(Optional.of(ruleWithSamples(300, 300, 300)));

        assertThat(service().recommendedBuffer(7L)).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void unknownPlaceOrNullRecommendsZero() {
        when(repository.findByPlaceId(9L)).thenReturn(Optional.empty());

        assertThat(service().recommendedBuffer(9L)).isEqualTo(Duration.ZERO);
        assertThat(service().recommendedBuffer(null)).isEqualTo(Duration.ZERO);
    }

    /** 回報事件進來 → 建立或累積該地點統計;無地點行程略過。 */
    @Test
    void outcomeEventAccumulatesSample() {
        when(repository.findByPlaceId(7L)).thenReturn(Optional.empty());

        service().onScheduleOutcomeRecorded(new ScheduleOutcomeRecorded(1L, 7L, false, 30));

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
}
