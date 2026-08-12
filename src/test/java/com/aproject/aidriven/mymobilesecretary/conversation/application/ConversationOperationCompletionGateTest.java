package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationOperationCompletionGateTest {

    @Test
    void unregisteredCapabilityCannotClaimCompletion() {
        ConversationOperationCompletionGate gate =
                new ConversationOperationCompletionGate(List.of());

        assertThatThrownBy(() -> gate.requireCompleted("route", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void incompleteCapabilityCannotClaimCompletion() {
        UUID workflowId = UUID.randomUUID();
        ConversationOperationCompletionContributor contributor =
                mock(ConversationOperationCompletionContributor.class);
        when(contributor.operationKind()).thenReturn("route");
        when(contributor.assess(workflowId))
                .thenReturn(Optional.of(new ConversationOperationCompletionContributor.Assessment(
                        "route",
                        workflowId,
                        ConversationOperationCompletionContributor.Status.INCOMPLETE)));
        ConversationOperationCompletionGate gate =
                new ConversationOperationCompletionGate(List.of(contributor));

        assertThatThrownBy(() -> gate.requireCompleted("route", workflowId))
                .isInstanceOf(IllegalStateException.class);
    }
}
