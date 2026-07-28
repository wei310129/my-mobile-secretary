package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationFocusTransitionTest {

    @Test
    void persistedTransitionAdvancesOneRevisionAndDoesNotExposeMutableDeliveryState() {
        FocusTransition transition = FocusTransition.create(
                new ConversationScopeKey("c".repeat(64), 1), WorkspaceChannel.TEST,
                4, 5, FocusTransitionType.SWITCH, UUID.randomUUID(), UUID.randomUUID(),
                "d".repeat(64), Instant.parse("2026-07-21T00:00:00Z"));

        assertThat(transition.getAfterRevision()).isEqualTo(5);
        assertThatThrownBy(() -> FocusTransition.create(
                new ConversationScopeKey("c".repeat(64), 1), WorkspaceChannel.TEST,
                4, 6, FocusTransitionType.SWITCH, null, UUID.randomUUID(),
                "d".repeat(64), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
