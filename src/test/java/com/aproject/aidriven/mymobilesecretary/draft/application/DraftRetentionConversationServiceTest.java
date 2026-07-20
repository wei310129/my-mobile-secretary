package com.aproject.aidriven.mymobilesecretary.draft.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class DraftRetentionConversationServiceTest {
    @Test
    void parsesChineseHalfHour() {
        assertThat(DraftRetentionConversationService.parseTime("晚上十一點半"))
                .contains(LocalTime.of(23, 30));
    }

    @Test
    void independentResetOnlyChangesRequestedSetting() {
        DraftRetentionService retention = mock(DraftRetentionService.class);
        when(retention.resetLatest(false, true)).thenReturn("已改回預設提醒");
        DraftRetentionConversationService conversation =
                new DraftRetentionConversationService(retention);

        var result = conversation.answer("這份草稿的提醒時間改回預設", () -> { });

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().message()).contains("預設提醒");
        verify(retention).resetLatest(false, true);
    }

    @Test
    void combinedSentenceChangesExpiryAndReminderTogether() {
        DraftRetentionService retention = mock(DraftRetentionService.class);
        when(retention.customizeLatest(30, 2, LocalTime.of(21, 0)))
                .thenReturn("已套用個別設定");
        DraftRetentionConversationService conversation =
                new DraftRetentionConversationService(retention);

        var result = conversation.answer(
                "這份草稿保留30天，提醒時間到期前2天晚上9點", () -> { });

        assertThat(result).isPresent();
        verify(retention).customizeLatest(30, 2, LocalTime.of(21, 0));
    }
}
