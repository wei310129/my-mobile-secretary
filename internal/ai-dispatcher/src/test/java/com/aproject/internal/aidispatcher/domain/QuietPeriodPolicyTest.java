package com.aproject.internal.aidispatcher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuietPeriodPolicyTest {

    private static final Instant BASE = Instant.parse("2026-07-17T00:00:00Z");
    private final QuietPeriodPolicy policy =
            new QuietPeriodPolicy(Duration.ofMinutes(5), Duration.ofMinutes(30));

    @Test
    void waitsFiveMinutesAfterTheLastEvent() {
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(BASE, BASE.plusSeconds(60), 10), null, null);

        assertThat(schedule.quietUntil()).isEqualTo(BASE.plus(Duration.ofMinutes(6)));
        assertThat(schedule.forceUntil()).isEqualTo(BASE.plus(Duration.ofMinutes(30)));
        assertThat(schedule.eligibleAt()).isEqualTo(schedule.quietUntil());
    }

    @Test
    void maximumWaitWinsWhenEventsKeepArriving() {
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(BASE, BASE.plus(Duration.ofMinutes(29)), 50), null, null);

        assertThat(schedule.quietUntil()).isEqualTo(BASE.plus(Duration.ofMinutes(34)));
        assertThat(schedule.eligibleAt()).isEqualTo(BASE.plus(Duration.ofMinutes(30)));
    }

    @Test
    void waitsAgainFromRunFinishWhenEventsAccumulatedDuringARun() {
        Instant finishedAt = BASE.plus(Duration.ofMinutes(10));
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(
                        BASE.plus(Duration.ofMinutes(2)),
                        BASE.plus(Duration.ofMinutes(9)),
                        5),
                finishedAt,
                null);

        assertThat(schedule.quietUntil()).isEqualTo(finishedAt.plus(Duration.ofMinutes(5)));
        assertThat(schedule.eligibleAt()).isEqualTo(schedule.quietUntil());
    }

    @Test
    void startsImmediatelyAfterFinishWhenMaximumWaitAlreadyExpired() {
        Instant finishedAt = BASE.plus(Duration.ofMinutes(40));
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(BASE, BASE.plus(Duration.ofMinutes(35)), 20),
                finishedAt,
                null);

        assertThat(schedule.eligibleAt()).isEqualTo(BASE.plus(Duration.ofMinutes(30)));
        assertThat(schedule.isEligibleAt(finishedAt)).isTrue();
    }

    @Test
    void retryBackoffMayExtendBeyondTheBatchingMaximum() {
        Instant retryAt = BASE.plus(Duration.ofMinutes(45));
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(BASE, BASE.plus(Duration.ofMinutes(29)), 50),
                null,
                retryAt);

        assertThat(schedule.forceUntil()).isEqualTo(BASE.plus(Duration.ofMinutes(30)));
        assertThat(schedule.eligibleAt()).isEqualTo(retryAt);
    }

    @Test
    void exactDeadlineIsEligible() {
        DispatchSchedule schedule = policy.schedule(
                new PendingEventWindow(BASE, BASE, 1), null, null);

        assertThat(schedule.isEligibleAt(schedule.eligibleAt().minusNanos(1))).isFalse();
        assertThat(schedule.isEligibleAt(schedule.eligibleAt())).isTrue();
    }

    @Test
    void rejectsAnInvalidEventWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingEventWindow(
                BASE.plusSeconds(1), BASE, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingEventWindow(BASE, BASE, 0));
    }
}
