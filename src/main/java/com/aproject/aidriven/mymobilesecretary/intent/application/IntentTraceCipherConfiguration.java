package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Base64;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Selects encrypted raw storage or the explicitly disabled local/test behavior. */
@Configuration(proxyBeanMethods = false)
class IntentTraceCipherConfiguration {

    @Bean
    SecretTextCipher intentTraceSecretTextCipher(IntentTraceProperties properties, Environment environment) {
        String encodedKey = properties.encryptionKey();
        if (encodedKey == null || encodedKey.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
                return new NoRawSecretTextCipher();
            }
            throw new IllegalStateException(
                    "app.intent.trace.encryption-key is required outside local/test profiles");
        }

        try {
            return new AesGcmSecretTextCipher(
                    Base64.getDecoder().decode(encodedKey), properties.encryptionKeyId());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.intent.trace.encryption-key must be Base64 encoded AES-128/192/256 key material", e);
        }
    }
}
