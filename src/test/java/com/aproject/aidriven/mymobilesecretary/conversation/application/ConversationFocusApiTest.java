package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import org.junit.jupiter.api.Test;

class ConversationFocusApiTest {

    @Test
    void responseEnvelopeContainsOneRenderedPublicNoticeWithoutInternalFields() {
        FocusResponseEnvelope envelope = FocusResponseEnvelope.withNotice("主要回覆",
                FocusTransitionNotice.forTransition(FocusTransitionType.ENTER, null, "大阪旅行", null),
                new ConversationFocusReplyDecorator(new FocusTransitionNoticeRenderer()));

        assertThat(envelope.message()).contains("目前先處理「大阪旅行」")
                .doesNotContain("digest", "revision", "ENTER");
        assertThat(envelope.notice().currentSafeLabel()).isEqualTo("大阪旅行");
    }

    @Test
    void intentResultCarriesTypedNoticeThroughTheSharedPublicEnvelope() {
        IntentResult result = IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "主要回覆")
                .withFocusNotice(FocusTransitionNotice.forTransition(
                        FocusTransitionType.RESUME, null, "大阪旅行", null));

        assertThat(result.responseEnvelope().message()).contains("繼續處理「大阪旅行」")
                .doesNotContain("RESUME", "revision");
    }
}
