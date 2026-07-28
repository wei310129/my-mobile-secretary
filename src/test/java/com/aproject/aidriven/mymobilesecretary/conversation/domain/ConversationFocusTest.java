package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationFocusTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final ConversationScopeKey SCOPE = new ConversationScopeKey("a".repeat(64), 1);

    @Test
    void resourceAndWorkflowAnchorsAreExclusiveAndClosedFocusRequiresReason() {
        ConversationFocus resource = ConversationFocus.resource(
                SCOPE, WorkspaceChannel.TEST, "TASK", "task:42", "繳電費", NOW);
        ConversationFocus workflow = ConversationFocus.workflow(
                SCOPE, WorkspaceChannel.TEST, ConversationFocusRootKind.WORKFLOW,
                "PROJECT", UUID.randomUUID(), "大阪旅行", NOW);

        assertThat(resource.getRoutingKey()).isEqualTo("task:42");
        assertThat(resource.getWorkflowId()).isNull();
        assertThat(workflow.getRoutingKey()).isNull();
        assertThat(workflow.getWorkflowId()).isNotNull();

        workflow.close(ConversationFocusCloseReason.USER_CLOSED, NOW.plusSeconds(1));

        assertThat(workflow.getStatus()).isEqualTo(ConversationFocusStatus.CLOSED);
        assertThat(workflow.getCloseReason()).isEqualTo(ConversationFocusCloseReason.USER_CLOSED);
        assertThatThrownBy(() -> workflow.resume(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activityChangesAsAnAtomicCodeAndLabelPair() {
        ConversationFocus focus = ConversationFocus.resource(
                SCOPE, WorkspaceChannel.TEST, "TASK", "task:42", "繳電費", NOW);

        assertThatThrownBy(() -> focus.changeActivity("PAYMENT", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        focus.changeActivity("PAYMENT", "繳款方式", NOW.plusSeconds(1));

        assertThat(focus.getActivityCode()).isEqualTo("PAYMENT");
        assertThat(focus.getActivityLabel()).isEqualTo("繳款方式");
    }
}
