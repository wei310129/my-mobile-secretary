package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.integration.development-feed", name = "enabled", havingValue = "true")
public class DevelopmentFeedSecurityConfiguration {

    static final String FEED_PATH = "/internal/integration/v1/development-events";

    @Bean
    @Order(1)
    SecurityFilterChain developmentFeedSecurity(HttpSecurity http,
                                                DevelopmentFeedProperties properties)
            throws Exception {
        DevelopmentFeedAuthenticationFilter authenticationFilter =
                new DevelopmentFeedAuthenticationFilter(properties);
        return http
                .securityMatcher(FEED_PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, FEED_PATH).permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(authenticationFilter, AuthorizationFilter.class)
                .build();
    }
}
