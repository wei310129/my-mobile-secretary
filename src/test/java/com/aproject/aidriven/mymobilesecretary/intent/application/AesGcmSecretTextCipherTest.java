package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmSecretTextCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptsWithRandomNonceAndAuthenticatesOnDecrypt() {
        AesGcmSecretTextCipher cipher = new AesGcmSecretTextCipher(KEY, "trace-key-v1");

        SecretTextCipher.EncryptedText first = cipher.encrypt("private input").orElseThrow();
        SecretTextCipher.EncryptedText second = cipher.encrypt("private input").orElseThrow();

        assertThat(first.payload()).isNotEqualTo(second.payload());
        assertThat(new String(first.payload(), StandardCharsets.UTF_8)).doesNotContain("private input");
        assertThat(cipher.decrypt(first)).contains("private input");
    }

    @Test
    void rejectsTamperedCiphertextWithoutDisclosingPlaintext() {
        AesGcmSecretTextCipher cipher = new AesGcmSecretTextCipher(KEY, "trace-key-v1");
        SecretTextCipher.EncryptedText encrypted = cipher.encrypt("private input").orElseThrow();
        byte[] tampered = encrypted.payload();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(
                new SecretTextCipher.EncryptedText(encrypted.keyId(), tampered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private input");
    }

    @Test
    void draftToStringAlwaysRedactsRawExchange() {
        IntentDecisionTraceDraft draft = IntentDecisionTraceDraft.builder(UUID.randomUUID(), "LINE")
                .rawExchange("secret input", "secret output")
                .build();

        assertThat(draft.toString())
                .contains("raw=REDACTED")
                .doesNotContain("secret input", "secret output");
    }
}
