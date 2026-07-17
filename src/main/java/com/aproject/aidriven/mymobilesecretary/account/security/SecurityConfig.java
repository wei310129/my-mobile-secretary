package com.aproject.aidriven.mymobilesecretary.account.security;

import com.aproject.aidriven.mymobilesecretary.account.application.WorkspaceAccessService;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Production is fail-closed: every REST endpoint requires a bearer token except LINE's
 * independently HMAC-verified webhook and basic health probes. Local/test explicitly disable it.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
    SecurityFilterChain authenticatedApi(HttpSecurity http,
                                         RestWorkspaceContextFilter workspaceContextFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/line/webhook", "/actuator/health", "/actuator/info")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .addFilterBefore(workspaceContextFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    SecurityFilterChain localCompatibility(HttpSecurity http,
                                           RestWorkspaceContextFilter workspaceContextFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(workspaceContextFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    RestWorkspaceContextFilter restWorkspaceContextFilter(
            @Value("${app.security.enabled:true}") boolean securityEnabled,
            RestWorkspaceProperties properties,
            AppUserRepository userRepository,
            WorkspaceAccessService workspaceAccessService,
            SecurityAuditService securityAuditService) {
        return new RestWorkspaceContextFilter(securityEnabled, properties, userRepository,
                workspaceAccessService, securityAuditService);
    }

    /** The filter belongs inside Spring Security, after JWT authentication; prevent servlet duplication. */
    @Bean
    FilterRegistrationBean<RestWorkspaceContextFilter> disableContainerWorkspaceFilter(
            RestWorkspaceContextFilter filter) {
        FilterRegistrationBean<RestWorkspaceContextFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
