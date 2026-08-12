package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationVoiceProfileService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretaryFeedbackVariantPolicyTest {

    @Test
    void positiveAndNegativePoolsEachContainTenDistinctTruthfulReplies() {
        ConversationVoiceProfileService.Settings defaults =
                ConversationVoiceProfileService.Settings.defaults();
        List<String> praise = new ArrayList<>();
        List<String> dissatisfaction = new ArrayList<>();

        for (int index = 0; index < SecretaryFeedbackVariantPolicy.VARIANT_COUNT; index++) {
            praise.add(SecretaryFeedbackVariantPolicy.praise(index, defaults));
            SecretaryFeedbackVariantPolicy.Reply negative =
                    SecretaryFeedbackVariantPolicy.dissatisfaction(index, defaults);
            dissatisfaction.add(negative.fact() + "\n" + negative.question());
            assertThat(negative.question().chars().filter(value -> value == '？').count())
                    .isEqualTo(1);
        }

        assertThat(praise).doesNotHaveDuplicates().hasSize(10);
        assertThat(dissatisfaction).doesNotHaveDuplicates().hasSize(10);
        assertThat(praise).allSatisfy(reply -> assertThat(reply)
                .doesNotContain("我會記住", "我會維持", "我會繼續", "重新處理"));
        assertThat(dissatisfaction).allSatisfy(reply -> assertThat(reply)
                .doesNotContain("我會重新處理", "我會依", "我會調整", "已重新處理"));
    }

    @Test
    void configuredNamesAreUsedOnlyInANaturalFeedbackVariant() {
        ConversationVoiceProfileService.Settings settings =
                new ConversationVoiceProfileService.Settings("小賈", "老闆", 2);

        assertThat(SecretaryFeedbackVariantPolicy.praise(0, settings))
                .isEqualTo("謝謝老闆，能幫上忙，小賈也很開心。");
        assertThat(SecretaryFeedbackVariantPolicy.dissatisfaction(0, settings).fact())
                .startsWith("老闆，");
        assertThat(SecretaryFeedbackVariantPolicy.praise(1, settings))
                .doesNotContain("小賈", "老闆");
    }
}
