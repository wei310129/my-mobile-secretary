package com.aproject.internal.aidispatcher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SessionBindingAdminPropertiesTest {

    @Test
    void permitsACompletelyDisabledManagementApi() {
        SessionBindingAdminProperties properties =
                new SessionBindingAdminProperties(false, "");

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.adminToken()).isEmpty();
    }

    @Test
    void requiresAStrongTokenWhenEnabled() {
        assertThatThrownBy(() -> new SessionBindingAdminProperties(true, "too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }
}
