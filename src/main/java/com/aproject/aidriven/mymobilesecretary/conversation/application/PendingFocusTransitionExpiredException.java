package com.aproject.aidriven.mymobilesecretary.conversation.application;

/** A candidate has become stale; callers must request a fresh Java decision. */
public final class PendingFocusTransitionExpiredException extends IllegalStateException {
    public PendingFocusTransitionExpiredException() {
        super("pending focus transition is expired");
    }
}
