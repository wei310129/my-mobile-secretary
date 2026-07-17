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
                .encodeToString("v2:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(unsupported))
                .isInstanceOf(ResponseStatusException.class);
    }
}
