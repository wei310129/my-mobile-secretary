package com.aproject.aidriven.mymobilesecretary.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WorkSchoolSuspensionDraftTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void userImageStartsPendingAndOfficialResultIsExplicitlySeparated() {
        WorkSchoolSuspensionDraft draft = WorkSchoolSuspensionDraft.pending(
                LocalDate.of(2026, 7, 9), "臺北市：停止上班、停止上課",
                NOW.plusSeconds(600), NOW);

        draft.verified("官方頁面可核對", "https://www.dgpa.gov.tw/", true, NOW.plusSeconds(30));

        assertThat(draft.getStatus())
                .isEqualTo(WorkSchoolSuspensionDraft.Status.OFFICIAL_CONFIRMED);
        assertThatThrownBy(() -> draft.decline(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }
}
