package com.aproject.aidriven.mymobilesecretary.api.line;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds a signed LINE JSON envelope before MVC allocates its {@code byte[]} request body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LineWebhookBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/line/webhook".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        try {
            filterChain.doFilter(
                    new BoundedRequest(request, MAX_BODY_BYTES), response);
        } catch (PayloadTooLargeException tooLarge) {
            rejectIfPossible(response);
        } catch (ServletException wrapped) {
            if (!causedByPayloadTooLarge(wrapped)) {
                throw wrapped;
            }
            rejectIfPossible(response);
        }
    }

    private static void rejectIfPossible(HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        }
    }

    private static boolean causedByPayloadTooLarge(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final int maxBytes;

        private BoundedRequest(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        private BoundedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int boundedLength = (int) Math.min(length, maxBytes - count + 1);
            int read = delegate.read(bytes, offset, Math.max(1, boundedLength));
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(int amount) throws PayloadTooLargeException {
            count += amount;
            if (count > maxBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}
