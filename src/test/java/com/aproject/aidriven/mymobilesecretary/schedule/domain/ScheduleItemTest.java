package com.aproject.aidriven.mymobilesecretary.schedule.domain;

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
}
