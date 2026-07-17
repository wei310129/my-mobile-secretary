package com.aproject.internal.aidispatcher.session.api;

import com.aproject.internal.aidispatcher.config.SessionBindingAdminProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class SessionBindingAdminAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    SessionBindingAdminAuthenticator(SessionBindingAdminProperties properties) {
        this.expectedToken = properties.adminToken().getBytes(StandardCharsets.UTF_8);
    }

    void authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new SessionBindingAuthenticationException();
        }
        byte[] suppliedToken = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, suppliedToken)) {
            throw new SessionBindingAuthenticationException();
        }
    }
}
