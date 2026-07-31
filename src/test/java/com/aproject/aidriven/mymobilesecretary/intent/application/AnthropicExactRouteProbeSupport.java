package com.aproject.aidriven.mymobilesecretary.intent.application;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Test-only timing probe for the existing synchronous Anthropic request path.
 *
 * <p>The probe never reads or stores request headers, request bodies, response text or
 * user input. It records only attempt timing and provider-supplied token counters.
 */
final class AnthropicExactRouteProbeSupport {

    private static final ThreadLocal<Collector> CURRENT = new ThreadLocal<>();

    private AnthropicExactRouteProbeSupport() {
    }

    static Scope open(String scenarioId) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Anthropic exact-route probe scope is already active");
        }
        Collector collector = new Collector(scenarioId, System.nanoTime());
        CURRENT.set(collector);
        return new Scope(collector);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        RestClientCustomizer anthropicExactRouteRestClientCustomizer() {
            ClientHttpRequestInterceptor interceptor =
                    AnthropicExactRouteProbeSupport::intercept;
            return builder -> builder.requestInterceptor(interceptor);
        }

        @Bean
        ObservationHandler<ChatModelObservationContext>
                anthropicExactRouteObservationHandler() {
            return new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return context instanceof ChatModelObservationContext;
                }

                @Override
                public void onStop(ChatModelObservationContext context) {
                    Collector collector = CURRENT.get();
                    if (collector == null || context.getResponse() == null
                            || context.getResponse().getMetadata() == null
                            || context.getResponse().getMetadata().getUsage() == null) {
                        return;
                    }
                    Object nativeUsage = context.getResponse()
                            .getMetadata()
                            .getUsage()
                            .getNativeUsage();
                    if (nativeUsage instanceof AnthropicApi.Usage usage) {
                        collector.recordUsage(usage);
                    }
                }
            };
        }
    }

    private static ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        Collector collector = CURRENT.get();
        if (collector == null || !isAnthropicMessagesRequest(request)) {
            return execution.execute(request, body);
        }

        Attempt attempt = collector.beginAttempt();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            attempt.markHeadersReceived();
            return new TimingClientHttpResponse(response, attempt);
        } catch (IOException | RuntimeException exception) {
            attempt.markFailed();
            throw exception;
        }
    }

    private static boolean isAnthropicMessagesRequest(HttpRequest request) {
        String path = request.getURI().getPath();
        return path != null && path.endsWith("/messages");
    }

    static final class Scope implements AutoCloseable {

        private final Collector collector;
        private boolean closed;

        private Scope(Collector collector) {
            this.collector = collector;
        }

        Snapshot snapshot() {
            return collector.snapshot();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (CURRENT.get() == collector) {
                CURRENT.remove();
            }
        }
    }

    record Snapshot(
            String scenarioId,
            long scopeStartedNanos,
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheCreationInputTokens,
            Integer cacheReadInputTokens,
            List<AttemptSnapshot> attempts) {

        int attemptCount() {
            return attempts.size();
        }

        AttemptSnapshot finalAttempt() {
            return attempts.isEmpty() ? AttemptSnapshot.empty() : attempts.getLast();
        }

        Long retryGapMillis() {
            if (attempts.size() < 2) {
                return 0L;
            }
            long totalNanos = 0L;
            for (int index = 1; index < attempts.size(); index++) {
                long previousFinished = attempts.get(index - 1).finishedNanos();
                long nextStarted = attempts.get(index).startedNanos();
                if (previousFinished > 0L && nextStarted > previousFinished) {
                    totalNanos += nextStarted - previousFinished;
                }
            }
            return nanosToMillis(totalNanos);
        }
    }

    record AttemptSnapshot(
            long startedNanos,
            long headersReceivedNanos,
            long firstBodyByteNanos,
            long bodyFinishedNanos,
            long failedNanos) {

        static AttemptSnapshot empty() {
            return new AttemptSnapshot(0L, 0L, 0L, 0L, 0L);
        }

        Long responseHeadersMillis() {
            return elapsedMillis(startedNanos, headersReceivedNanos);
        }

        Long responseFirstByteMillis() {
            return elapsedMillis(startedNanos, firstBodyByteNanos);
        }

        Long responseBodyMillis() {
            return elapsedMillis(firstBodyByteNanos, bodyFinishedNanos);
        }

        long finishedNanos() {
            return Math.max(Math.max(headersReceivedNanos, bodyFinishedNanos), failedNanos);
        }
    }

    private static final class Collector {

        private final String scenarioId;
        private final long scopeStartedNanos;
        private final List<Attempt> attempts = new ArrayList<>();
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer cacheCreationInputTokens;
        private Integer cacheReadInputTokens;

        private Collector(String scenarioId, long scopeStartedNanos) {
            this.scenarioId = scenarioId;
            this.scopeStartedNanos = scopeStartedNanos;
        }

        Attempt beginAttempt() {
            Attempt attempt = new Attempt(System.nanoTime());
            attempts.add(attempt);
            return attempt;
        }

        void recordUsage(AnthropicApi.Usage usage) {
            inputTokens = usage.inputTokens();
            outputTokens = usage.outputTokens();
            cacheCreationInputTokens = usage.cacheCreationInputTokens();
            cacheReadInputTokens = usage.cacheReadInputTokens();
        }

        Snapshot snapshot() {
            return new Snapshot(
                    scenarioId,
                    scopeStartedNanos,
                    inputTokens,
                    outputTokens,
                    cacheCreationInputTokens,
                    cacheReadInputTokens,
                    attempts.stream().map(Attempt::snapshot).toList());
        }
    }

    private static final class Attempt {

        private final long startedNanos;
        private long headersReceivedNanos;
        private long firstBodyByteNanos;
        private long bodyFinishedNanos;
        private long failedNanos;

        private Attempt(long startedNanos) {
            this.startedNanos = startedNanos;
        }

        void markHeadersReceived() {
            headersReceivedNanos = System.nanoTime();
        }

        void markFirstBodyByte() {
            if (firstBodyByteNanos == 0L) {
                firstBodyByteNanos = System.nanoTime();
            }
        }

        void markBodyFinished() {
            if (bodyFinishedNanos == 0L) {
                bodyFinishedNanos = System.nanoTime();
            }
        }

        void markFailed() {
            if (failedNanos == 0L) {
                failedNanos = System.nanoTime();
            }
        }

        AttemptSnapshot snapshot() {
            return new AttemptSnapshot(
                    startedNanos,
                    headersReceivedNanos,
                    firstBodyByteNanos,
                    bodyFinishedNanos,
                    failedNanos);
        }
    }

    private static final class TimingClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final Attempt attempt;
        private InputStream timedBody;

        private TimingClientHttpResponse(ClientHttpResponse delegate, Attempt attempt) {
            this.delegate = delegate;
            this.attempt = attempt;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public synchronized InputStream getBody() throws IOException {
            if (timedBody == null) {
                timedBody = new TimingInputStream(delegate.getBody(), attempt);
            }
            return timedBody;
        }

        @Override
        public void close() {
            attempt.markBodyFinished();
            delegate.close();
        }
    }

    private static final class TimingInputStream extends FilterInputStream {

        private final Attempt attempt;

        private TimingInputStream(InputStream delegate, Attempt attempt) {
            super(delegate);
            this.attempt = attempt;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            observe(value < 0 ? -1 : 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            observe(count);
            return count;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                attempt.markBodyFinished();
            }
        }

        private void observe(int count) {
            if (count > 0) {
                attempt.markFirstBodyByte();
            } else if (count < 0) {
                attempt.markBodyFinished();
            }
        }
    }

    private static Long elapsedMillis(long startedNanos, long finishedNanos) {
        if (startedNanos <= 0L || finishedNanos < startedNanos) {
            return null;
        }
        return nanosToMillis(finishedNanos - startedNanos);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }
}
