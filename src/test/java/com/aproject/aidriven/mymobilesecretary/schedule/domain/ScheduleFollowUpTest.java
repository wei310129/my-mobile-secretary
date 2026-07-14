package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 追蹤詢問狀態機測試:SCHEDULED → ASKED → ANSWERED 單向前進,
 * 改期只在還沒發問時允許、且只能提前。
 */
class ScheduleFollowUpTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final Instant DUE = Instant.parse("2026-07-14T03:00:00Z");

    private ScheduleFollowUp scheduled() {
        return ScheduleFollowUp.planAt(1L, DUE, NOW);
    }

    @Test
    void planStartsAsScheduled() {
        ScheduleFollowUp followUp = scheduled();

        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.SCHEDULED);
        assertThat(followUp.getDueAt()).isEqualTo(DUE);
        assertThat(followUp.getAskedAt()).isNull();
    }

    /** GPS 路徑算出更早的時間 → 提前。 */
    @Test
    void advancesWhenCandidateIsEarlier() {
        ScheduleFollowUp followUp = scheduled();
        Instant earlier = DUE.minusSeconds(1800);

        assertThat(followUp.advanceIfEarlier(earlier, NOW)).isTrue();
        assertThat(followUp.getDueAt()).isEqualTo(earlier);
    }

    /** 較晚的候選時間不得把詢問往後拖。 */
    @Test
    void laterCandidateDoesNotPostpone() {
        ScheduleFollowUp followUp = scheduled();

        assertThat(followUp.advanceIfEarlier(DUE.plusSeconds(60), NOW)).isFalse();
        assertThat(followUp.getDueAt()).isEqualTo(DUE);
    }

    /** 已發問後任何路徑都不得再改期。 */
    @Test
    void askedFollowUpCannotBeRescheduled() {
        ScheduleFollowUp followUp = scheduled();
        followUp.markAsked(NOW);

        assertThat(followUp.advanceIfEarlier(DUE.minusSeconds(1800), NOW)).isFalse();
    }

    @Test
    void askedThenAnsweredIsTheNormalPath() {
        ScheduleFollowUp followUp = scheduled();
        followUp.markAsked(NOW);

        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.ASKED);
        assertThat(followUp.getAskedAt()).isEqualTo(NOW);

        followUp.markAnswered(NOW.plusSeconds(60));
        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.ANSWERED);
    }

    /** 還沒問使用者就先回報 → SCHEDULED 直接 ANSWERED,合法。 */
    @Test
    void scheduledCanBeAnsweredDirectly() {
        ScheduleFollowUp followUp = scheduled();
        followUp.markAnswered(NOW);

        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.ANSWERED);
    }

    /** 答過就終止:再問、再答都非法。 */
    @Test
    void answeredIsTerminal() {
        ScheduleFollowUp followUp = scheduled();
        followUp.markAnswered(NOW);

        assertThatThrownBy(() -> followUp.markAsked(NOW)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> followUp.markAnswered(NOW)).isInstanceOf(BusinessException.class);
    }

    @Test
    void cannotAskTwice() {
        ScheduleFollowUp followUp = scheduled();
        followUp.markAsked(NOW);

        assertThatThrownBy(() -> followUp.markAsked(NOW)).isInstanceOf(BusinessException.class);
    }
}
