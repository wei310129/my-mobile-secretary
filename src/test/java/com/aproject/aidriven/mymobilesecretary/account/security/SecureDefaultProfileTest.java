package com.aproject.aidriven.mymobilesecretary.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class SecureDefaultProfileTest {

    @Test
    void omittedProfileKeepsAuthenticationAndFailClosedWorkspaceDefaults() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yaml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> application = sources.getFirst();
        assertThat(application.getProperty("spring.profiles.default")).isNull();
        assertThat(application.getProperty("app.security.enabled")).isEqualTo(true);
        assertThat(application.getProperty("app.workspace.legacy-fallback-enabled")).isEqualTo(false);
    }
}
