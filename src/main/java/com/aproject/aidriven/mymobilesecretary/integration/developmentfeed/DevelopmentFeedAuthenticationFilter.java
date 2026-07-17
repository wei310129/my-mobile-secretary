package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

final class DevelopmentFeedAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;
    private final WorkspaceContext integrationContext;

    DevelopmentFeedAuthenticationFilter(DevelopmentFeedProperties properties) {
        this.expectedToken = properties.bearerToken().getBytes(StandardCharsets.UTF_8);
        this.integrationContext = new WorkspaceContext(
                properties.actorId(), properties.workspaceId(), WorkspaceChannel.INTEGRATION);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!validBearerToken(authorization)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(integrationContext)) {
            filterChain.doFilter(request, response);
        }
    }

    private boolean validBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, supplied);
    }
}
