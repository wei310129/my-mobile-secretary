package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Encrypts and persists one compact decision trace without affecting the user-facing operation. */
@Service
public class IntentDecisionTraceService {

    private static final Logger log = LoggerFactory.getLogger(IntentDecisionTraceService.class);

    private final IntentDecisionTraceWriter writer;
    private final SecretTextCipher cipher;
    private final IntentTraceProperties properties;
    private final Clock clock;

    public IntentDecisionTraceService(IntentDecisionTraceWriter writer, SecretTextCipher cipher,
            IntentTraceProperties properties, Clock clock) {
        this.writer = writer;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records a trace in an independent transaction.
     *
     * @return true only when the trace was persisted; every failure is contained and returns false
     */
    public boolean recordSafely(IntentDecisionTraceDraft draft) {
        UUID requestId = draft == null ? null : draft.requestId();
        if (draft == null) {
            log.warn("Intent decision trace skipped [requestId=missing, cause=NullDraft]");
            return false;
        }

        try {
            EncryptedExchange encrypted = encryptExchangeSafely(draft);
            Instant now = Instant.now(clock);
            Instant summaryExpiresAt = now.plus(properties.summaryRetention());
            Instant rawExpiresAt = encrypted.hasRaw()
                    ? now.plus(properties.rawRetention())
                    : null;
            IntentDecisionTrace trace = IntentDecisionTrace.capture(
                    draft,
                    encrypted.inputPayload(),
                    encrypted.outputPayload(),
                    encrypted.keyId(),
                    now,
                    rawExpiresAt,
                    summaryExpiresAt);
            writer.write(trace);
            return true;
        } catch (Exception e) {
            log.warn("Intent decision trace persistence failed [requestId={}, cause={}]",
                    requestId, e.getClass().getSimpleName());
            return false;
        }
    }

    private EncryptedExchange encryptExchangeSafely(IntentDecisionTraceDraft draft) {
        if (!cipher.enabled()) {
            return EncryptedExchange.empty();
        }

        try {
            Optional<SecretTextCipher.EncryptedText> input = cipher.encrypt(draft.rawInput());
            Optional<SecretTextCipher.EncryptedText> output = cipher.encrypt(draft.rawOutput());
            String keyId = input.map(SecretTextCipher.EncryptedText::keyId)
                    .or(() -> output.map(SecretTextCipher.EncryptedText::keyId))
                    .orElse(null);
            if (input.isPresent() && output.isPresent()
                    && !input.get().keyId().equals(output.get().keyId())) {
                throw new IllegalStateException("Intent trace inputs used different encryption keys");
            }
            return new EncryptedExchange(
                    input.map(SecretTextCipher.EncryptedText::payload).orElse(null),
                    output.map(SecretTextCipher.EncryptedText::payload).orElse(null),
                    keyId);
        } catch (Exception e) {
            log.warn("Intent decision trace raw payload omitted [requestId={}, cause={}]",
                    draft.requestId(), e.getClass().getSimpleName());
            return EncryptedExchange.empty();
        }
    }

    private record EncryptedExchange(byte[] inputPayload, byte[] outputPayload, String keyId) {

        static EncryptedExchange empty() {
            return new EncryptedExchange(null, null, null);
        }

        boolean hasRaw() {
            return inputPayload != null || outputPayload != null;
        }
    }
}
