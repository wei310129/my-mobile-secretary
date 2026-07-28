package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationFocusHeadTest {

    @Test
    void advancesExactlyOnceAndRejectsStaleRevision() {
        ConversationFocusHead head = ConversationFocusHead.create(
                new ConversationScopeKey("b".repeat(64), 1), WorkspaceChannel.TEST,
                Instant.parse("2026-07-21T00:00:00Z"));

        assertThat(head.advance(0, Instant.parse("2026-07-21T00:00:01Z"))).isEqualTo(1);
        assertThatThrownBy(() -> head.advance(0, Instant.parse("2026-07-21T00:00:02Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }
}
