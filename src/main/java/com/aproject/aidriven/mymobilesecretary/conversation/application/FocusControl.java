package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.UUID;

/** Trusted, typed focus directive; it is never created from free-form model output alone. */
public sealed interface FocusControl permits FocusControl.None, FocusControl.EnterWorkflow,
        FocusControl.EnterAsyncWork, FocusControl.EnterResource, FocusControl.ChangeSubfocus,
        FocusControl.SwitchWorkflow, FocusControl.SwitchResource, FocusControl.Resume,
        FocusControl.Exit, FocusControl.Close, FocusControl.Invalidate {

    static None none() { return None.INSTANCE; }
    static Exit exit() { return Exit.INSTANCE; }
    static Close close() { return Close.INSTANCE; }

    enum None implements FocusControl { INSTANCE }
    record EnterWorkflow(String domain, UUID workflowId, String safeLabel) implements FocusControl { }
    record EnterAsyncWork(String domain, UUID workflowId, String safeLabel) implements FocusControl { }
    record EnterResource(String domain, String routingKey, String safeLabel) implements FocusControl { }
    record ChangeSubfocus(String code, String safeLabel) implements FocusControl { }
    record SwitchWorkflow(String domain, UUID workflowId, String safeLabel) implements FocusControl { }
    record SwitchResource(String domain, String routingKey, String safeLabel) implements FocusControl { }
    record Resume(UUID focusId) implements FocusControl { }
    enum Exit implements FocusControl { INSTANCE }
    enum Close implements FocusControl { INSTANCE }
    enum Invalidate implements FocusControl { INSTANCE }
}
