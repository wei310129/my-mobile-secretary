package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActorRouteOperationPreferenceTest {

    @Test
    void storesIndependentTypedMinutesAndOnlyRevisesOnChange() {
        Instant start = Instant.parse("2026-08-05T01:00:00Z");
        ActorRouteOperationPreference preference =
                ActorRouteOperationPreference.create(start);

        preference.setGeneral(10, 15, start.plusSeconds(1));
        preference.setParking(12, start.plusSeconds(2));
        preference.setRideHailWait(8, start.plusSeconds(3));

        assertThat(preference.getGeneralBeforeMinutes()).isEqualTo(10);
        assertThat(preference.getGeneralAfterMinutes()).isEqualTo(15);
        assertThat(preference.getParkingMinutes()).isEqualTo(12);
        assertThat(preference.getRideHailWaitMinutes()).isEqualTo(8);
        assertThat(preference.getRevision()).isEqualTo(4);

        preference.setParking(12, start.plusSeconds(4));
        assertThat(preference.getRevision()).isEqualTo(4);
    }

    @Test
    void rejectsMinutesOutsideTheBoundedPolicy() {
        ActorRouteOperationPreference preference =
                ActorRouteOperationPreference.create(Instant.parse("2026-08-05T01:00:00Z"));

        assertThatThrownBy(() -> preference.setGeneral(-1, 10, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preference.setParking(241, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preference.setRideHailWait(300, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
