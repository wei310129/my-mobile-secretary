package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import org.junit.jupiter.api.Test;

class ConversationFocusNoticeContractTest {

    @Test
    void eachTransitionHasOnlyAudienceSafeFactsRequiredForThatTransition() {
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(
                FocusTransitionType.SWITCH, "整理報稅", "大阪旅行", null);

        assertThat(notice.type()).isEqualTo(FocusTransitionType.SWITCH);
        assertThat(notice.previousSafeLabel()).isEqualTo("整理報稅");
        assertThat(notice.currentSafeLabel()).isEqualTo("大阪旅行");
    }

    @Test
    void idsDigestsAndInternalEnumWordsCannotEnterPublicNoticeFacts() {
        assertThatThrownBy(() -> FocusTransitionNotice.forTransition(
                FocusTransitionType.ENTER, null,
                "00000000-0000-0000-0000-000000000001", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FocusTransitionNotice.forTransition(
                FocusTransitionType.ENTER, null, "a".repeat(64), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
