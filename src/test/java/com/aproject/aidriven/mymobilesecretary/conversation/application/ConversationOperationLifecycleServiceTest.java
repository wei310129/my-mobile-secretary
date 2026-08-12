package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationOperationLifecycleServiceTest {

    @Test
    void lifecycleContextCannotOmitPreservedOrUnresolvedFacts() {
        assertThatThrownBy(() -> new ConversationOperationLifecycleContributor.LifecycleContext(
                        "規劃前往高鐵桃園站",
                        "建立出發地點",
                        java.util.List.of(),
                        java.util.List.of("尚未提供地址")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationOperationLifecycleContributor.LifecycleContext(
                        "規劃前往高鐵桃園站",
                        "建立出發地點",
                        java.util.List.of("出發時間已保留"),
                        java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discardCurrentUnfinishedUsesTheTypedOwnerAndReturnsItsMutationCount() {
        ConversationOperationLifecycleRegistry registry =
                mock(ConversationOperationLifecycleRegistry.class);
        var operation = new ConversationOperationLifecycleContributor.Operation(
                "route", "CALENDAR_DRAFT", UUID.randomUUID(), null, "route", true);
        when(registry.findCurrentUnfinished("route")).thenReturn(java.util.Optional.of(operation));
        when(registry.close(operation)).thenReturn(
                new ConversationOperationLifecycleContributor.CloseOutcome(1, true));
        ConversationOperationLifecycleService service = new ConversationOperationLifecycleService(
                registry,
                mock(ConversationPendingQuestionService.class),
                mock(ConversationFocusService.class));

        assertThat(service.discardCurrentUnfinished("route")).isEqualTo(1);
        verify(registry).close(operation);
    }

    @Test
    void closeCurrentCoordinatesDomainPendingAndFocusExactlyOnce() {
        ConversationOperationLifecycleRegistry registry =
                mock(ConversationOperationLifecycleRegistry.class);
        ConversationPendingQuestionService pending =
                mock(ConversationPendingQuestionService.class);
        ConversationFocusService focuses = mock(ConversationFocusService.class);
        UUID workflowId = UUID.randomUUID();
        var operation = new ConversationOperationLifecycleContributor.Operation(
                "route", "CALENDAR_DRAFT", workflowId, null, "前往高鐵桃園站", true);
        when(registry.close(operation)).thenReturn(
                new ConversationOperationLifecycleContributor.CloseOutcome(1, true));
        when(pending.cancelCurrent(workflowId, "a".repeat(64))).thenReturn(true);
        when(focuses.closeAll("a".repeat(64))).thenReturn(2);
        ConversationOperationLifecycleService service =
                new ConversationOperationLifecycleService(registry, pending, focuses);

        assertThat(service.closeCurrent(operation, "a".repeat(64)))
                .satisfies(result -> {
                    assertThat(result.domainMutationCount()).isEqualTo(1);
                    assertThat(result.pendingMutationCount()).isEqualTo(1);
                    assertThat(result.focusMutationCount()).isEqualTo(2);
                    assertThat(result.committedDataPreserved()).isTrue();
                });
        verify(registry).close(operation);
        verify(pending).cancelCurrent(workflowId, "a".repeat(64));
        verify(focuses).closeAll("a".repeat(64));
    }
}
