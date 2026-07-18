package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScheduleItemTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void recurrenceCutoffCannotBeBeforeFirstOccurrence() {
        ScheduleItem item = ScheduleItem.propose("每週六上課",
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T02:00:00Z"), null, NOW);

        assertThatThrownBy(() -> item.repeat(ScheduleItem.Recurrence.WEEKLY,
                LocalDate.of(2026, 7, 17), NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                                .isEqualTo("INVALID_RECURRENCE_UNTIL"));
    }

    @Test
    void recurringScheduleCannotMoveBeyondItsCutoff() {
        ScheduleItem item = ScheduleItem.propose("每週六上課",
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T02:00:00Z"), null, NOW);
        item.confirm(NOW);
        item.repeat(ScheduleItem.Recurrence.WEEKLY, LocalDate.of(2026, 7, 31), NOW);

        assertThatThrownBy(() -> item.reschedule(
                Instant.parse("2026-08-01T01:00:00Z"),
                Instant.parse("2026-08-01T02:00:00Z"), NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                                .isEqualTo("INVALID_RECURRENCE_UNTIL"));
    }

    @Test
    void familyPointEventKeepsResponsibilityWithoutInventingDurationOrActorBusyTime() {
        Instant at = Instant.parse("2026-07-18T08:00:00Z");

        ScheduleItem item = ScheduleItem.proposePoint("阿公接兒子回家", at, null, NOW);
        item.assignResponsibility("阿公", false);

        assertThat(item.getStartAt()).isEqualTo(at);
        assertThat(item.getEndAt()).isEqualTo(at.plusSeconds(60));
        assertThat(item.isEndTimeExplicit()).isFalse();
        assertThat(item.getResponsiblePerson()).isEqualTo("阿公");
        assertThat(item.isCountsForActorBusy()).isFalse();
    }
}
