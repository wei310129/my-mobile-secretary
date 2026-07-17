package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Encrypts the short-lived raw parts of an intent trace.
 *
 * <p>The trace service only persists {@link EncryptedText}; callers must never log plaintext or
 * ciphertext. Production implementations can later delegate this interface to a KMS-backed
 * envelope-encryption provider without changing the trace schema.</p>
 */
public interface SecretTextCipher {

    /** Returns an encrypted value, or empty when raw trace storage is intentionally disabled. */
    Optional<EncryptedText> encrypt(String plainText);

    /** Decrypts a value for an explicitly authorized diagnostic workflow. */
    Optional<String> decrypt(EncryptedText encryptedText);

    /** Whether raw trace storage is enabled for this runtime. */
    boolean enabled();

    /** Ciphertext plus the non-secret key reference needed for later rotation/decryption. */
    final class EncryptedText {

        private final String keyId;
        private final byte[] payload;

        public EncryptedText(String keyId, byte[] payload) {
            this.keyId = Objects.requireNonNull(keyId, "keyId");
            this.payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        }

        public String keyId() {
            return keyId;
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }
}
