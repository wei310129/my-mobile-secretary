package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IntentTraceCipherConfigurationTest {

    private final IntentTraceCipherConfiguration configuration = new IntentTraceCipherConfiguration();

    @Test
    void localProfileDisablesRawStorageWhenKeyIsAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SecretTextCipher cipher = configuration.intentTraceSecretTextCipher(properties(null), environment);

        assertThat(cipher.enabled()).isFalse();
        assertThat(cipher.encrypt("must not be retained")).isEmpty();
    }

    @Test
    void productionFailsClosedWhenEncryptionKeyIsAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> configuration.intentTraceSecretTextCipher(
                properties(null), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-key is required");
    }

    @Test
    void configuresAesGcmFromBase64Key() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        String encodedKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        SecretTextCipher cipher = configuration.intentTraceSecretTextCipher(
                properties(encodedKey), environment);

        assertThat(cipher.decrypt(cipher.encrypt("raw").orElseThrow())).contains("raw");
    }

    private static IntentTraceProperties properties(String key) {
        return new IntentTraceProperties(key, "trace-key-v1",
                Duration.ofDays(7), Duration.ofDays(90));
    }
}
