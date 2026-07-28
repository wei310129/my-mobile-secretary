package com.aproject.aidriven.mymobilesecretary.account.security.idempotency;

import com.aproject.aidriven.mymobilesecretary.intent.application.AesGcmSecretTextCipher;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentTraceProperties;
import com.aproject.aidriven.mymobilesecretary.intent.application.SecretTextCipher;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Encrypts replayable public responses independently from optional raw intent traces. */
@Configuration(proxyBeanMethods = false)
class IdempotencyResponseCipherConfiguration {

    @Bean
    @Qualifier("idempotencyResponseCipher")
    SecretTextCipher idempotencyResponseCipher(IntentTraceProperties properties,
                                                Environment environment) {
        String encodedKey = properties.encryptionKey();
        if (encodedKey == null || encodedKey.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException(
                        "app.intent.trace.encryption-key is required for idempotency replay");
            }
            byte[] ephemeralKey = new byte[32];
            new SecureRandom().nextBytes(ephemeralKey);
            try {
                return new AesGcmSecretTextCipher(ephemeralKey, "idempotency-ephemeral-v1");
            } finally {
                java.util.Arrays.fill(ephemeralKey, (byte) 0);
            }
        }
        try {
            return new AesGcmSecretTextCipher(
                    Base64.getDecoder().decode(encodedKey), properties.encryptionKeyId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "app.intent.trace.encryption-key must be valid Base64 AES key material",
                    exception);
        }
    }
}
