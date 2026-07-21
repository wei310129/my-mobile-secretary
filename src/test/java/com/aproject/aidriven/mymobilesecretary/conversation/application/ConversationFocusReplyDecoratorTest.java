package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import org.junit.jupiter.api.Test;

class ConversationFocusReplyDecoratorTest {

    @Test
    void decoratesEveryPersistedTransitionWithJavaOwnedSafeNotice() {
        ConversationFocusReplyDecorator decorator = new ConversationFocusReplyDecorator(
                new FocusTransitionNoticeRenderer());

        for (FocusTransitionType type : FocusTransitionType.values()) {
            String reply = decorator.decorate("主要回覆", FocusTransitionNotice.forTransition(
                    type, "原工作", "新工作", "子題"));

            assertThat(reply).contains("主要回覆").doesNotContain("FocusTransition", "revision");
        }
    }

    @Test
    void keepHasNoNoticeAndDoesNotReplayAdditionalText() {
        ConversationFocusReplyDecorator decorator = new ConversationFocusReplyDecorator(
                new FocusTransitionNoticeRenderer());

        assertThat(decorator.decorate("主要回覆", null)).isEqualTo("主要回覆");
    }
}
