package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreeSlotServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final Instant FROM = Instant.parse("2026-07-19T05:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-07-19T09:00:00Z");

    @Mock
    private ScheduleItemRepository repository;
    @Mock
    private ScheduleItem familyOnly;
    @Mock
    private ScheduleItem actorBusy;

    @Test
    void familyOnlyEventDoesNotConsumeActorsFreeTime() {
        when(familyOnly.isCountsForActorBusy()).thenReturn(false);
        when(repository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of(familyOnly));

        List<FreeSlotService.Slot> slots = service().suggest(
                FROM, UNTIL, Duration.ofHours(2), null);

        assertThat(slots).containsExactly(
                new FreeSlotService.Slot(FROM, FROM.plus(Duration.ofHours(2))),
                new FreeSlotService.Slot(FROM.plus(Duration.ofHours(2)), UNTIL));
    }

    @Test
    void actorsConfirmedScheduleStillBlocksCandidate() {
        when(actorBusy.isCountsForActorBusy()).thenReturn(true);
        when(actorBusy.getStartAt()).thenReturn(Instant.parse("2026-07-19T06:00:00Z"));
        when(actorBusy.getEndAt()).thenReturn(Instant.parse("2026-07-19T07:00:00Z"));
        when(repository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of(actorBusy));

        assertThat(service().suggest(FROM, UNTIL, Duration.ofHours(2), null))
                .containsExactly(new FreeSlotService.Slot(
                        Instant.parse("2026-07-19T07:00:00Z"), UNTIL));
    }

    private FreeSlotService service() {
        return new FreeSlotService(repository, CLOCK);
    }
}
