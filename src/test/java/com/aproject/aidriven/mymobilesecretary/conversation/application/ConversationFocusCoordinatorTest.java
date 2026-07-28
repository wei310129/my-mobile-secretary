package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import org.junit.jupiter.api.Test;

class ConversationFocusCoordinatorTest {

    @Test
    void missingDirectiveClarifiesWithoutCallingFocusMutationService() {
        ConversationFocusService service = mock(ConversationFocusService.class);
        ConversationFocusCoordinator coordinator = new ConversationFocusCoordinator(
                new ConversationFocusTransitionPolicy(), service);

        assertThat(coordinator.coordinate(FocusBehavior.START_OR_SWITCH, FocusControl.none(), true))
                .isEqualTo(FocusDecision.clarify());

        verifyNoInteractions(service);
    }

    @Test
    void controlDecisionExposesTypedTransitionBeforeAnyRendererExists() {
        ConversationFocusCoordinator coordinator = new ConversationFocusCoordinator(
                new ConversationFocusTransitionPolicy(), mock(ConversationFocusService.class));

        assertThat(coordinator.coordinate(FocusBehavior.CONTROL, FocusControl.exit(), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.EXIT));
    }

    @Test
    void missingContributorFailsClosedBeforeAnyFutureEnterOrSwitchCanBeAuthorized() {
        ConversationFocusContributorRegistry registry = new ConversationFocusContributorRegistry(
                java.util.List.of());

        assertThatThrownBy(() -> registry.require("PROJECT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing focus contributor");
    }
}
