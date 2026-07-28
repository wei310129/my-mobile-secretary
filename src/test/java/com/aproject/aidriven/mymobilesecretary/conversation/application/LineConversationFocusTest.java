package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import org.junit.jupiter.api.Test;

class LineConversationFocusTest {

    @Test
    void lineRendererUsesTheEnvelopeTextOnceAndDoesNotExposeTypedInternals() {
        FocusResponseEnvelope envelope = FocusResponseEnvelope.withNotice("主要回覆",
                FocusTransitionNotice.forTransition(FocusTransitionType.EXIT, null, "報稅資料", null),
                new ConversationFocusReplyDecorator(new FocusTransitionNoticeRenderer()));

        assertThat(new LineFocusReplyRenderer().render(envelope))
                .contains("目前沒有正在處理的事項")
                .doesNotContain("EXIT", "FocusTransitionNotice", "revision");
    }
}
