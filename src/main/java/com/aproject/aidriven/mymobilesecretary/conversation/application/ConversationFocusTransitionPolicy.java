package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Combines catalog behavior and trusted controls; ambiguity always clarifies without mutation. */
@Component
public final class ConversationFocusTransitionPolicy {

    public FocusDecision decide(FocusBehavior behavior, FocusControl control, boolean hasActiveFocus) {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(control, "control");
        return switch (behavior) {
            case CONTINUE, ONE_SHOT_KEEP, NEVER_TOUCH -> control instanceof FocusControl.None
                    ? FocusDecision.keep() : FocusDecision.clarify();
            case START_OR_SWITCH -> decideStartOrSwitch(control, hasActiveFocus);
            case CONTROL, TERMINAL -> decideControl(control, hasActiveFocus);
        };
    }

    private FocusDecision decideStartOrSwitch(FocusControl control, boolean hasActiveFocus) {
        if (control instanceof FocusControl.Resume) {
            return FocusDecision.transition(FocusTransitionType.RESUME);
        }
        if (control instanceof FocusControl.ChangeSubfocus && hasActiveFocus) {
            return FocusDecision.transition(FocusTransitionType.CHANGE_SUBFOCUS);
        }
        if (isEnter(control) && !hasActiveFocus) {
            return FocusDecision.transition(FocusTransitionType.ENTER);
        }
        if (isSwitch(control) && hasActiveFocus) {
            return FocusDecision.transition(FocusTransitionType.SWITCH);
        }
        return FocusDecision.clarify();
    }

    private static boolean isEnter(FocusControl control) {
        return control instanceof FocusControl.EnterWorkflow
                || control instanceof FocusControl.EnterResource;
    }

    private static boolean isSwitch(FocusControl control) {
        return control instanceof FocusControl.SwitchWorkflow
                || control instanceof FocusControl.SwitchResource;
    }

    private FocusDecision decideControl(FocusControl control, boolean hasActiveFocus) {
        if (control instanceof FocusControl.Resume) {
            return FocusDecision.transition(FocusTransitionType.RESUME);
        }
        if (control instanceof FocusControl.InvalidateTarget) {
            return FocusDecision.transition(FocusTransitionType.INVALIDATE);
        }
        if (!hasActiveFocus) {
            return FocusDecision.clarify();
        }
        if (control instanceof FocusControl.ChangeSubfocus) {
            return FocusDecision.transition(FocusTransitionType.CHANGE_SUBFOCUS);
        }
        if (control instanceof FocusControl.Exit) {
            return FocusDecision.transition(FocusTransitionType.EXIT);
        }
        if (control instanceof FocusControl.Close) {
            return FocusDecision.transition(FocusTransitionType.CLOSE);
        }
        if (control instanceof FocusControl.Invalidate) {
            return FocusDecision.transition(FocusTransitionType.INVALIDATE);
        }
        return FocusDecision.clarify();
    }
}
