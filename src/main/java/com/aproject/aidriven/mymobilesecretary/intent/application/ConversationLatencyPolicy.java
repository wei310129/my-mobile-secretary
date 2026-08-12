package com.aproject.aidriven.mymobilesecretary.intent.application;

import org.springframework.stereotype.Service;

/** Predicts whether LINE should show non-message progress before conversation work starts. */
@Service
public class ConversationLatencyPolicy {

    public boolean shouldShowLoading(String text) {
        return SecretaryTurnRouter.route(text).isEmpty();
    }
}
