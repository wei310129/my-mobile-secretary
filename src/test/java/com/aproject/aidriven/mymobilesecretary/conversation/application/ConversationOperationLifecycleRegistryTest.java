package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationOperationLifecycleRegistryTest {

    @Test
    void duplicateOperationKindIsRejectedAtStartup() {
        ConversationOperationLifecycleContributor first = contributor("route");
        ConversationOperationLifecycleContributor duplicate = contributor("route");

        assertThatThrownBy(() -> new ConversationOperationLifecycleRegistry(
                List.of(first, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate operation lifecycle contributor");
    }

    @Test
    void twoContributorsClaimingOneReferenceFailClosed() {
        var reference = new ConversationOperationLifecycleContributor.Reference(
                "task", UUID.randomUUID(), null, null);
        var operation = new ConversationOperationLifecycleContributor.Operation(
                "route", "CALENDAR_DRAFT", reference.workflowId(), null, "路線", true);
        ConversationOperationLifecycleContributor first = contributor("route");
        ConversationOperationLifecycleContributor second = contributor("booking");
        when(first.resolve(reference)).thenReturn(Optional.of(operation));
        when(second.resolve(reference)).thenReturn(Optional.of(new
                ConversationOperationLifecycleContributor.Operation(
                        "booking", "BOOKING", reference.workflowId(), null, "訂位", true)));
        ConversationOperationLifecycleRegistry registry =
                new ConversationOperationLifecycleRegistry(List.of(first, second));

        assertThatThrownBy(() -> registry.resolve(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous operation lifecycle ownership");
    }

    @Test
    void duplicateStagingOwnersFailBeforeEitherContributorCanMutate() {
        IntentCommand command = mock(IntentCommand.class);
        ConversationOperationLifecycleContributor first = contributor("route");
        ConversationOperationLifecycleContributor second = contributor("other-route");
        when(first.supportsStaging(command)).thenReturn(true);
        when(second.supportsStaging(command)).thenReturn(true);
        ConversationOperationLifecycleRegistry registry =
                new ConversationOperationLifecycleRegistry(List.of(first, second));

        assertThatThrownBy(() -> registry.stage(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous staged operation lifecycle ownership");
        verify(first, never()).stage(command);
        verify(second, never()).stage(command);
    }

    private static ConversationOperationLifecycleContributor contributor(String kind) {
        ConversationOperationLifecycleContributor contributor =
                mock(ConversationOperationLifecycleContributor.class);
        when(contributor.operationKind()).thenReturn(kind);
        return contributor;
    }
}
