package com.aproject.aidriven.mymobilesecretary.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestCorrelationContextTest {

    @Test
    void nestedScopesRestoreThePreviousRequest() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        try (var ignored = RequestCorrelationContext.open(outer)) {
            assertThat(RequestCorrelationContext.currentId()).isEqualTo(outer);
            try (var nested = RequestCorrelationContext.open(inner)) {
                assertThat(RequestCorrelationContext.currentId()).isEqualTo(inner);
            }
            assertThat(RequestCorrelationContext.currentId()).isEqualTo(outer);
        }
    }
}
