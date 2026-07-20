package com.aproject.aidriven.mymobilesecretary.health.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BloodDonationRecordTest {
    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void eligibilityDateMustNotPrecedeDonation() {
        var record = BloodDonationRecord.create(LocalDate.of(2026, 7, 1), "公園捐血車",
                null, BloodDonationRecord.SourceType.IMAGE, NOW);

        assertThatThrownBy(() -> record.setNextEligibleDate(LocalDate.of(2026, 6, 30), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(record.getNextEligibleDate()).isNull();
    }

    @Test
    void explicitEligibilityDateCanBeSaved() {
        var record = BloodDonationRecord.create(LocalDate.of(2026, 7, 1), null,
                null, BloodDonationRecord.SourceType.USER, NOW);
        record.setNextEligibleDate(LocalDate.of(2026, 10, 3), NOW);
        assertThat(record.getNextEligibleDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }
}
