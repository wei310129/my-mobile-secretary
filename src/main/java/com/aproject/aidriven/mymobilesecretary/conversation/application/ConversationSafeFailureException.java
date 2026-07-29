package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Objects;

/**
 * Carries a pre-sanitized reply across a transactional boundary.
 *
 * <p>The exception must escape the atomic executor so Spring rolls back the domain/focus
 * transaction before the non-transactional caller turns it back into a public result.
 */
public final class ConversationSafeFailureException extends RuntimeException {

    private final IntentResult result;

    public ConversationSafeFailureException(IntentResult result) {
        super("conversation operation failed safely");
        this.result = Objects.requireNonNull(result, "result");
    }

    public IntentResult result() {
        return result;
    }
}
