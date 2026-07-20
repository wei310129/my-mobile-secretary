package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DevelopmentFeedCursorCodecTest {

    private final DevelopmentFeedCursorCodec codec = new DevelopmentFeedCursorCodec();

    @Test
    void cursorIsOpaqueVersionedAndRoundTrips() {
        String cursor = codec.encode(12345);

        assertThat(cursor).doesNotContain("12345");
        assertThat(codec.decode(cursor)).isEqualTo(12345);
        assertThat(codec.decode(null)).isZero();
    }

    @Test
    void malformedOrUnsupportedCursorIsRejected() {
        assertThatThrownBy(() -> codec.decode("not-base64!"))
                .isInstanceOf(ResponseStatusException.class);
        String unsupported = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(unsupported))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nonCanonicalNumericAndBase64FormsAreRejected() {
        for (String payload : java.util.List.of(
                "v2:", "v2:-1", "v2:+1", "v2:01", "v2:1x",
                "v2:9223372036854775808")) {
            String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> codec.decode(cursor))
                    .as(payload)
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> codec.decode(codec.encode(1) + "="))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void malformedUtf8IsRejectedInsteadOfBeingReplaced() {
        String malformedUtf8 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> codec.decode(malformedUtf8))
                .isInstanceOf(ResponseStatusException.class);
    }
}
