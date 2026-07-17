package com.aproject.aidriven.mymobilesecretary.shared.observability;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds one opaque request id to responses, structured traces and safe operational logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = parseOrCreate(request.getHeader(HEADER));
        response.setHeader(HEADER, requestId.toString());
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(requestId)) {
            MDC.put("requestId", requestId.toString());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private static UUID parseOrCreate(String candidate) {
        if (candidate != null && candidate.length() <= 36) {
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // Do not reflect arbitrary input into logs or the response.
            }
        }
        return UUID.randomUUID();
    }
}
