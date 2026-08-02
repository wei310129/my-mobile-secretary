package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.UUID;

/** Trusted, typed focus directive; it is never created from free-form model output alone. */
public sealed interface FocusControl permits FocusControl.None, FocusControl.EnterWorkflow,
        FocusControl.EnterAsyncWork, FocusControl.EnterResource, FocusControl.ChangeSubfocus,
        FocusControl.SwitchWorkflow, FocusControl.SwitchResource, FocusControl.Resume,
        FocusControl.Exit, FocusControl.Close, FocusControl.Invalidate,
        FocusControl.InvalidateTarget {

    static None none() { return None.INSTANCE; }
    static Exit exit() { return Exit.INSTANCE; }
    static Close close() { return Close.INSTANCE; }

    enum None implements FocusControl { INSTANCE }
    record EnterWorkflow(String domain, UUID workflowId, String safeLabel,
                         String activityCode, String activityLabel) implements FocusControl {
        public EnterWorkflow(String domain, UUID workflowId, String safeLabel) {
            this(domain, workflowId, safeLabel, null, null);
        }
    }
    record EnterAsyncWork(String domain, UUID workflowId, String safeLabel) implements FocusControl { }
    record EnterResource(String domain, String routingKey, String safeLabel) implements FocusControl { }
    record ChangeSubfocus(String code, String safeLabel) implements FocusControl { }
    record SwitchWorkflow(String domain, UUID workflowId, String safeLabel,
                          String activityCode, String activityLabel) implements FocusControl {
        public SwitchWorkflow(String domain, UUID workflowId, String safeLabel) {
            this(domain, workflowId, safeLabel, null, null);
        }
    }
    record SwitchResource(String domain, String routingKey, String safeLabel) implements FocusControl { }
    record Resume(UUID focusId, String safeLabel) implements FocusControl {
        public Resume(UUID focusId) {
            this(focusId, null);
        }
    }
    enum Exit implements FocusControl { INSTANCE }
    enum Close implements FocusControl { INSTANCE }
    enum Invalidate implements FocusControl { INSTANCE }
    record InvalidateTarget(UUID focusId) implements FocusControl { }
}
