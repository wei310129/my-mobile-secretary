package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonthlyOrdinalRecurrenceConversationServiceTest {

    @Mock private ScheduleService scheduleService;

    @Test
    void incompleteRuleAsksOnlyOneTypedQuestionAndDoesNotMutate() {
        AtomicInteger mutations = new AtomicInteger();
        var service = new MonthlyOrdinalRecurrenceConversationService(
                scheduleService,
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("Asia/Taipei")));

        IntentResult result = service.answer(
                "每月第一個星期一早上九點開會", null, mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.nextQuestion().code()).isEqualTo("monthly-ordinal.duration");
        assertThat(result.message())
                .contains("每次持續多久")
                .doesNotContain("行程要叫什麼名稱");
        assertThat(mutations).hasValue(0);
    }
}
