package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
class FamilyTransportConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));

    @Mock
    private ScheduleService scheduleService;

    @Test
    void explicitWifeDropOffAndGrandpaPickupCreateTwoNonActorPointEvents() {
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "禮拜六九點老婆送兒子去安親班，四點阿公接回板橋家，兩段都幫我記一下，別寫成是我接送",
                mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.BATCH_EXECUTED);
        assertThat(result.message())
                .contains("老婆送兒子到安親班", "阿公接兒子回板橋家", "原話沒有持續時間",
                        "不會算成你的忙碌", "不會", "由你接送");
        verify(scheduleService).createFamilyPointSchedule(
                "老婆送兒子到安親班", Instant.parse("2026-07-18T01:00:00Z"), null, "老婆");
        verify(scheduleService).createFamilyPointSchedule(
                "阿公接兒子回板橋家", Instant.parse("2026-07-18T08:00:00Z"), null, "阿公");
    }

    @Test
    void missingPickupAssignmentDoesNotUseThisTwoEventPath() {
        assertThat(service().answer(
                "禮拜六九點老婆送兒子去安親班，四點下課但還不知道誰接",
                () -> { })).isEmpty();

        verifyNoInteractions(scheduleService);
    }

    @Test
    void pastSameWeekdayTimeMovesToNextWeekInsteadOfCreatingInPast() {
        Clock late = Clock.fixed(
                Instant.parse("2026-07-18T04:00:00Z"), ZoneId.of("Asia/Taipei"));
        FamilyTransportConversationService service =
                new FamilyTransportConversationService(scheduleService, late);

        service.answer(
                "禮拜六九點老婆送兒子去安親班，四點阿公接回板橋家",
                () -> { }).orElseThrow();

        verify(scheduleService).createFamilyPointSchedule(
                "老婆送兒子到安親班", Instant.parse("2026-07-25T01:00:00Z"), null, "老婆");
    }

    private FamilyTransportConversationService service() {
        return new FamilyTransportConversationService(scheduleService, CLOCK);
    }
}
