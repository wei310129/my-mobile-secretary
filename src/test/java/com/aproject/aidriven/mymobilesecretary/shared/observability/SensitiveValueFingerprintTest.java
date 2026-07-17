package com.aproject.aidriven.mymobilesecretary.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveValueFingerprintTest {

    @Test
    void producesStableShortFingerprintWithoutLeakingInput() {
        String first = SensitiveValueFingerprint.of("U-sensitive-line-user-id");
        String second = SensitiveValueFingerprint.of("U-sensitive-line-user-id");

        assertThat(first).isEqualTo(second).hasSize(16);
        assertThat(first).doesNotContain("sensitive");
        assertThat(SensitiveValueFingerprint.of("U-other")).isNotEqualTo(first);
    }

    @Test
    void handlesMissingValuesWithoutHashingAmbiguity() {
        assertThat(SensitiveValueFingerprint.of(null)).isEqualTo("missing");
        assertThat(SensitiveValueFingerprint.of(" ")).isEqualTo("missing");
    }
}
