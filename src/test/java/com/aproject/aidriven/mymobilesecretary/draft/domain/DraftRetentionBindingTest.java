package com.aproject.aidriven.mymobilesecretary.draft.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.draft.application.DraftRetentionService;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class DraftRetentionBindingTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void initialDefaultsDeleteAtEndOfSeventhCalendarDay() {
        DraftRetentionPreference defaults = DraftRetentionPreference.suggested(NOW);

        DraftRetentionBinding binding = DraftRetentionBinding.create(
                DraftType.BANK_TRANSFER, 1L, "轉帳草稿", defaults, NOW);

        assertThat(binding.getEffectiveRetentionDays(defaults)).isEqualTo(7);
        assertThat(binding.getExpiresAt()).isEqualTo(Instant.parse("2030-01-08T16:00:00Z"));
        assertThat(binding.getRemindAt()).isEqualTo(Instant.parse("2030-01-08T12:00:00Z"));
        assertThat(DraftRetentionService.expiryDay(binding.getExpiresAt()))
                .isEqualTo(LocalDate.of(2030, 1, 8));
    }

    @Test
    void reminderMustBeAtLeastFiveMinutesAfterReceivedTime() {
        DraftRetentionPreference defaults = DraftRetentionPreference.suggested(NOW);
        DraftRetentionBinding tooClose = DraftRetentionBinding.create(
                DraftType.BANK_TRANSFER, 1L, "太近", defaults, NOW);
        DraftRetentionBinding boundary = DraftRetentionBinding.create(
                DraftType.BANK_TRANSFER, 2L, "邊界", defaults, NOW);

        assertThatThrownBy(() -> tooClose.customizeReminder(
                7, LocalTime.of(8, 4, 59), defaults, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("five minutes");
        boundary.customizeReminder(7, LocalTime.of(8, 5), defaults, NOW);
        assertThat(boundary.getRemindAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void retentionCannotExceedThirtyDays() {
        DraftRetentionPreference defaults = DraftRetentionPreference.suggested(NOW);
        DraftRetentionBinding binding = DraftRetentionBinding.create(
                DraftType.PRODUCT_OBSERVATION, 3L, "產品草稿", defaults, NOW);

        binding.customizeRetention(30, defaults, NOW);

        assertThat(binding.getEffectiveRetentionDays(defaults)).isEqualTo(30);
        assertThatThrownBy(() -> binding.customizeRetention(31, defaults, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 30");
    }

    @Test
    void expiryAndReminderCanIndependentlyUseCustomOrDefaultValues() {
        DraftRetentionPreference defaults = DraftRetentionPreference.suggested(NOW);
        DraftRetentionBinding binding = DraftRetentionBinding.create(
                DraftType.PRODUCT_OBSERVATION, 4L, "產品草稿", defaults, NOW);
        binding.customizeRetention(14, defaults, NOW);
        binding.customizeReminder(2, LocalTime.of(18, 0), defaults, NOW);

        defaults.changeRetentionDays(10, NOW.plusSeconds(1));
        defaults.changeReminder(1, LocalTime.of(21, 0), NOW.plusSeconds(1));
        binding.refreshDefaults(defaults, NOW.plusSeconds(1));

        assertThat(binding.isUsesDefaultRetention()).isFalse();
        assertThat(binding.isUsesDefaultReminder()).isFalse();
        assertThat(binding.getEffectiveRetentionDays(defaults)).isEqualTo(14);
        assertThat(binding.getRemindAt()).isEqualTo(Instant.parse("2030-01-13T10:00:00Z"));

        binding.useDefaultRetention(defaults, NOW.plusSeconds(2));
        assertThat(binding.getEffectiveRetentionDays(defaults)).isEqualTo(10);
        assertThat(binding.isUsesDefaultReminder()).isFalse();
        binding.useDefaultReminder(defaults, NOW.plusSeconds(3));
        assertThat(binding.isUsesDefaultReminder()).isTrue();
        assertThat(binding.getRemindAt()).isEqualTo(Instant.parse("2030-01-10T13:00:00Z"));
    }
}
