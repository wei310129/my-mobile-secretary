package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationFocusTransitionPolicyTest {

    private final ConversationFocusTransitionPolicy policy = new ConversationFocusTransitionPolicy();

    @Test
    void producesOneFailClosedDecisionForEachBehaviorAndControlCombination() {
        assertThat(policy.decide(FocusBehavior.CONTINUE, FocusControl.none(), true))
                .isEqualTo(FocusDecision.keep());
        assertThat(policy.decide(FocusBehavior.ONE_SHOT_KEEP, FocusControl.none(), true))
                .isEqualTo(FocusDecision.keep());
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH, FocusControl.none(), true))
                .isEqualTo(FocusDecision.clarify());
        assertThat(policy.decide(FocusBehavior.CONTROL, FocusControl.exit(), false))
                .isEqualTo(FocusDecision.clarify());
        assertThat(policy.decide(FocusBehavior.CONTROL, FocusControl.exit(), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.EXIT));
        assertThat(policy.decide(FocusBehavior.TERMINAL, FocusControl.close(), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.CLOSE));
    }

    @Test
    void coversEveryTypedTransitionWithoutLettingAskOrMissingDirectiveMutateFocus() {
        assertThat(policy.decide(FocusBehavior.ONE_SHOT_KEEP, FocusControl.none(), true))
                .isEqualTo(FocusDecision.keep());
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH,
                new FocusControl.EnterWorkflow("TASK", UUID.randomUUID(), "繳電費"), false))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.ENTER));
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH,
                new FocusControl.SwitchWorkflow("TASK", UUID.randomUUID(), "繳電費"), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.SWITCH));
        assertThat(policy.decide(FocusBehavior.CONTROL,
                new FocusControl.Resume(UUID.randomUUID()), false))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.RESUME));
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH,
                new FocusControl.Resume(UUID.randomUUID()), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.RESUME));
        assertThat(policy.decide(FocusBehavior.CONTROL,
                new FocusControl.ChangeSubfocus("PAYMENT", "繳款方式"), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.CHANGE_SUBFOCUS));
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH,
                new FocusControl.ChangeSubfocus("PACKING", "行李準備"), true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.CHANGE_SUBFOCUS));
        assertThat(policy.decide(FocusBehavior.TERMINAL, FocusControl.Invalidate.INSTANCE, true))
                .isEqualTo(FocusDecision.transition(FocusTransitionType.INVALIDATE));
        assertThat(policy.decide(FocusBehavior.START_OR_SWITCH, FocusControl.none(), false))
                .isEqualTo(FocusDecision.clarify());
    }
}
