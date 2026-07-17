package com.aproject.aidriven.mymobilesecretary.account.workspace;

import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceTenantProperties.class)
public class WorkspaceTenantConfiguration {

    private final WorkspaceTenantProperties properties;

    public WorkspaceTenantConfiguration(WorkspaceTenantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void configureFallbackPolicy() {
        WorkspaceContextHolder.configureLegacyFallback(properties.legacyFallbackEnabled());
    }

    @Bean
    CurrentTenantIdentifierResolver<UUID> workspaceTenantIdentifierResolver() {
        return new WorkspaceTenantIdentifierResolver();
    }

    @Bean
    HibernatePropertiesCustomizer workspaceTenantHibernateCustomizer(
            CurrentTenantIdentifierResolver<UUID> tenantIdentifierResolver) {
        return properties -> properties.put(
                MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                tenantIdentifierResolver);
    }
}
