package com.aproject.internal.aidispatcher.codex.cli;

import com.aproject.internal.aidispatcher.codex.CodexCompletion;
import com.aproject.internal.aidispatcher.codex.CodexExecutionObservation;
import com.aproject.internal.aidispatcher.codex.CodexExecutionPort;
import com.aproject.internal.aidispatcher.codex.CodexExecutionQuery;
import com.aproject.internal.aidispatcher.codex.CodexLifecycleService;
import com.aproject.internal.aidispatcher.codex.CodexStartCommand;
import com.aproject.internal.aidispatcher.codex.CodexStartReceipt;
import com.aproject.internal.aidispatcher.config.CliCodexProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ai-dispatcher.codex-cli",
        name = "enabled",
        havingValue = "true")
public final class CliCodexExecutionAdapter implements CodexExecutionPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(CliCodexExecutionAdapter.class);

    private final CliCodexProperties properties;
    private final CodexCliCommandFactory commandFactory;
    private final CodexProcessLauncher processLauncher;
    private final CodexExecutionAuditRepository auditRepository;
    private final CodexLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService watchdogExecutor;
    private final Map<String, RunningExecution> executions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CliCodexExecutionAdapter(CliCodexProperties properties,
                                    ObjectMapper objectMapper,
                                    JdbcTemplate jdbcTemplate,
                                    CodexLifecycleService lifecycleService,
                                    Clock clock) {
        this(properties, objectMapper, lifecycleService, clock,
                new JavaCodexProcessLauncher(),
                new CodexExecutionAuditRepository(jdbcTemplate),
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "codex-cli-watchdog");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    CliCodexExecutionAdapter(CliCodexProperties properties,
                             ObjectMapper objectMapper,
                             CodexLifecycleService lifecycleService,
                             Clock clock,
                             CodexProcessLauncher processLauncher,
                             CodexExecutionAuditRepository auditRepository,
                             ExecutorService workerExecutor,
                             ScheduledExecutorService watchdogExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
        this.processLauncher = processLauncher;
        this.auditRepository = auditRepository;
        this.workerExecutor = workerExecutor;
        this.watchdogExecutor = watchdogExecutor;
        this.commandFactory = new CodexCliCommandFactory(
                properties, new CodexPromptRenderer(objectMapper));
        this.commandFactory.validateConfiguration();
        long intervalMillis = properties.heartbeatInterval().toMillis();
        watchdogExecutor.scheduleWithFixedDelay(
                this::watchdogSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public CodexStartReceipt startCodex(CodexStartCommand command) {
        if (closed.get()) {
            throw new IllegalStateException("Codex CLI adapter is shutting down");
        }
        CodexCliProcessSpec processSpec = commandFactory.create(command);
        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now(clock);
        CodexManagedProcess process;
        try {
            process = processLauncher.start(processSpec);
        } catch (IOException startFailure) {
            throw new IllegalStateException("Could not start the configured Codex executable", startFailure);
        }

        RunningExecution execution = new RunningExecution(
                executionId, command, processSpec, process, startedAt,
                new CodexJsonlParser(objectMapper, properties.maximumJsonLineLength()),
                new BoundedTextBuffer(properties.maximumStderrCharacters()));
        try {
            auditRepository.recordStarted(
                    executionId, command, process.pid(), startedAt);
            if (executions.putIfAbsent(executionId, execution) != null) {
                throw new IllegalStateException("Generated duplicate Codex execution handle");
            }
            workerExecutor.submit(() -> supervise(execution));
        } catch (RuntimeException registrationFailure) {
            executions.remove(executionId);
            process.destroyBeforeDispatch();
            throw registrationFailure;
        }
        return new CodexStartReceipt(executionId, startedAt);
    }

    @Override
    public CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
        if (query.externalExecutionId() == null || query.externalExecutionId().isBlank()) {
            return CodexExecutionObservation.unknown(
                    "CLI_EXECUTION_HANDLE_MISSING", query.requestedAt());
        }
        RunningExecution execution = executions.get(query.externalExecutionId());
        if (execution != null) {
            CodexExecutionObservation terminal = execution.terminalObservation.get();
            if (terminal != null) {
                return terminal;
            }
            String forcedUnknown = execution.forcedUnknownCode.get();
            if (forcedUnknown != null) {
                return CodexExecutionObservation.unknown(forcedUnknown, query.requestedAt());
            }
            if (execution.process.isAlive()) {
                return CodexExecutionObservation.running(
                        execution.executionId, query.requestedAt());
            }
            return CodexExecutionObservation.unknown(
                    "CLI_SUPERVISOR_RECONCILING", query.requestedAt());
        }
        return observationFromAudit(query);
    }

    private CodexExecutionObservation observationFromAudit(CodexExecutionQuery query) {
        CodexExecutionAuditSnapshot audit = auditRepository.find(query.externalExecutionId());
        if (audit == null || "RUNNING".equals(audit.status())) {
            return CodexExecutionObservation.unknown(
                    "CLI_PROCESS_NOT_ATTACHED", query.requestedAt());
        }
        if ("SUCCEEDED".equals(audit.status())) {
            return CodexExecutionObservation.succeeded(
                    audit.externalExecutionId(), audit.diagnosticCode(), audit.finishedAt());
        }
        if ("FAILED".equals(audit.status())) {
            return CodexExecutionObservation.failed(
                    audit.externalExecutionId(), audit.diagnosticCode(), audit.finishedAt());
        }
        return CodexExecutionObservation.unknown(
                audit.diagnosticCode() == null
                        ? "CLI_OUTCOME_UNKNOWN" : audit.diagnosticCode(),
                query.requestedAt());
    }

    private void supervise(RunningExecution execution) {
        AtomicReference<Throwable> protocolFailure = new AtomicReference<>();
        Future<?> stdoutReader = workerExecutor.submit(
                () -> readStdout(execution, protocolFailure));
        Future<?> stderrReader = workerExecutor.submit(() -> readStderr(execution));
        try {
            execution.promptWriteStarted.set(true);
            try (var standardInput = execution.process.standardInput()) {
                standardInput.write(execution.processSpec.standardInput()
                        .getBytes(StandardCharsets.UTF_8));
            }
            int exitCode = execution.process.waitFor();
            awaitReader(stdoutReader);
            awaitReader(stderrReader);
            finishExecution(execution, exitCode, protocolFailure.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            retainUnknown(execution, "CLI_SUPERVISOR_INTERRUPTED", Instant.now(clock));
        } catch (RuntimeException | IOException supervisorFailure) {
            String code = execution.promptWriteStarted.get()
                    ? "CLI_SUPERVISOR_FAILED_AFTER_DISPATCH"
                    : "CLI_SUPERVISOR_FAILED_BEFORE_DISPATCH";
            retainUnknown(execution, code, Instant.now(clock));
            LOGGER.error("Codex CLI supervisor failed for execution {}", execution.executionId,
                    supervisorFailure);
        }
    }

    private void readStdout(RunningExecution execution,
                            AtomicReference<Throwable> protocolFailure) {
        try (Reader reader = new InputStreamReader(
                execution.process.standardOutput(), StandardCharsets.UTF_8)) {
            BoundedLineReader lines = new BoundedLineReader(
                    reader, properties.maximumJsonLineLength());
            String line;
            while ((line = lines.readLine()) != null) {
                if (protocolFailure.get() == null) {
                    try {
                        execution.parser.accept(line);
                        Instant progressAt = Instant.now(clock);
                        execution.lastProgressAt.set(progressAt);
                    } catch (RuntimeException parseFailure) {
                        protocolFailure.compareAndSet(null, parseFailure);
                    }
                }
            }
        } catch (IOException | RuntimeException readFailure) {
            protocolFailure.compareAndSet(null, readFailure);
        }
    }

    private void readStderr(RunningExecution execution) {
        try (Reader reader = new InputStreamReader(
                execution.process.standardError(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                execution.stderr.append(buffer, 0, count);
            }
        } catch (IOException readFailure) {
            LOGGER.debug("Could not fully drain Codex stderr for execution {}",
                    execution.executionId, readFailure);
        }
    }

    private void finishExecution(RunningExecution execution,
                                 int exitCode,
                                 Throwable protocolFailure) {
        Instant finishedAt = Instant.now(clock);
        CodexCliProgress progress = execution.parser.snapshot();
        TerminalDecision decision = terminalDecision(
                execution.command.externalSessionId(), exitCode, progress, protocolFailure);
        try {
            auditRepository.recordTerminal(
                    execution.executionId, decision.auditStatus(), decision.terminalEvent(),
                    exitCode, progress, decision.diagnosticCode(),
                    execution.stderr.snapshot(), finishedAt);
        } catch (RuntimeException auditFailure) {
            retainUnknown(execution, "CLI_TERMINAL_AUDIT_FAILED", finishedAt);
            LOGGER.error("Could not persist Codex CLI terminal audit for execution {}",
                    execution.executionId, auditFailure);
            return;
        }

        CodexExecutionObservation observation = switch (decision.auditStatus()) {
            case "SUCCEEDED" -> CodexExecutionObservation.succeeded(
                    execution.executionId, decision.diagnosticCode(), finishedAt);
            case "FAILED" -> CodexExecutionObservation.failed(
                    execution.executionId, decision.diagnosticCode(), finishedAt);
            default -> CodexExecutionObservation.unknown(
                    decision.diagnosticCode(), finishedAt);
        };
        execution.terminalObservation.set(observation);
        try {
            if ("SUCCEEDED".equals(decision.auditStatus())) {
                lifecycleService.onCodexFinish(
                        execution.command.runId(), execution.command.fencingToken(),
                        new CodexCompletion(
                                CodexCompletion.Status.SUCCEEDED,
                                decision.diagnosticCode(), finishedAt));
            } else if ("FAILED".equals(decision.auditStatus())) {
                lifecycleService.onCodexFinish(
                        execution.command.runId(), execution.command.fencingToken(),
                        new CodexCompletion(
                                CodexCompletion.Status.FAILED,
                                decision.diagnosticCode(), finishedAt));
            } else {
                lifecycleService.onCodexOutcomeUnknown(
                        execution.command.runId(), execution.command.fencingToken(),
                        decision.diagnosticCode(), finishedAt);
            }
        } catch (RuntimeException callbackFailure) {
            LOGGER.error("Could not deliver Codex CLI terminal callback for execution {}",
                    execution.executionId, callbackFailure);
        } finally {
            executions.remove(execution.executionId, execution);
        }
    }

    private TerminalDecision terminalDecision(String expectedSessionId,
                                                int exitCode,
                                                CodexCliProgress progress,
                                                Throwable protocolFailure) {
        if (protocolFailure != null) {
            return TerminalDecision.unknown("CLI_JSONL_PROTOCOL_ERROR", "PROTOCOL_ERROR");
        }
        if (!progress.threadStarted()
                || !expectedSessionId.equals(progress.reportedThreadId())) {
            return TerminalDecision.unknown("CLI_SESSION_ID_MISMATCH", "THREAD_MISMATCH");
        }
        if (exitCode == 0 && progress.turnCompleted()
                && !progress.turnFailed() && !progress.errorObserved()) {
            return TerminalDecision.succeeded();
        }
        if (!progress.turnStarted() && !progress.turnCompleted() && !progress.turnFailed()) {
            return TerminalDecision.failed("CLI_EXIT_BEFORE_TURN_" + exitCode);
        }
        return TerminalDecision.unknown(
                progress.turnFailed() ? "CLI_TURN_FAILED_SIDE_EFFECTS_UNKNOWN"
                        : "CLI_EXIT_AFTER_TURN_STARTED_" + exitCode,
                progress.turnFailed() ? "turn.failed" : "PROCESS_EXIT");
    }

    private void watchdogSafely() {
        try {
            Instant now = Instant.now(clock);
            for (RunningExecution execution : executions.values()) {
                watchdog(execution, now);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Codex CLI watchdog iteration failed", failure);
        }
    }

    private void watchdog(RunningExecution execution, Instant now) {
        if (execution.terminalObservation.get() != null
                || execution.forcedUnknownCode.get() != null) {
            return;
        }
        if (elapsed(execution.startedAt, now).compareTo(properties.maximumRuntime()) >= 0) {
            retainUnknown(execution, "CLI_MAXIMUM_RUNTIME_EXCEEDED", now);
            return;
        }
        if (elapsed(execution.lastProgressAt.get(), now)
                .compareTo(properties.noProgressTimeout()) >= 0) {
            retainUnknown(execution, "CLI_NO_PROGRESS_TIMEOUT", now);
            return;
        }
        if (execution.process.isAlive()) {
            try {
                auditRepository.recordProgress(
                        execution.executionId, execution.lastProgressAt.get());
                lifecycleService.heartbeat(
                        execution.command.runId(), execution.command.fencingToken());
            } catch (RuntimeException heartbeatFailure) {
                LOGGER.warn("Codex CLI heartbeat delivery failed for execution {}",
                        execution.executionId, heartbeatFailure);
            }
        }
    }

    private void retainUnknown(RunningExecution execution, String code, Instant observedAt) {
        if (!execution.forcedUnknownCode.compareAndSet(null, code)) {
            return;
        }
        try {
            lifecycleService.onCodexOutcomeUnknown(
                    execution.command.runId(), execution.command.fencingToken(), code, observedAt);
        } catch (RuntimeException callbackFailure) {
            LOGGER.error("Could not retain uncertain Codex CLI outcome for execution {}",
                    execution.executionId, callbackFailure);
        }
    }

    private static Duration elapsed(Instant start, Instant end) {
        return Duration.between(start, end).isNegative()
                ? Duration.ZERO : Duration.between(start, end);
    }

    private static void awaitReader(Future<?> reader) {
        try {
            reader.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining Codex output", interrupted);
        } catch (java.util.concurrent.ExecutionException readerFailure) {
            throw new IllegalStateException("Could not drain Codex output", readerFailure.getCause());
        }
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        watchdogExecutor.shutdown();
        workerExecutor.shutdown();
    }

    private static final class RunningExecution {
        private final String executionId;
        private final CodexStartCommand command;
        private final CodexCliProcessSpec processSpec;
        private final CodexManagedProcess process;
        private final Instant startedAt;
        private final AtomicReference<Instant> lastProgressAt;
        private final CodexJsonlParser parser;
        private final BoundedTextBuffer stderr;
        private final AtomicBoolean promptWriteStarted = new AtomicBoolean();
        private final AtomicReference<String> forcedUnknownCode = new AtomicReference<>();
        private final AtomicReference<CodexExecutionObservation> terminalObservation =
                new AtomicReference<>();

        private RunningExecution(String executionId,
                                 CodexStartCommand command,
                                 CodexCliProcessSpec processSpec,
                                 CodexManagedProcess process,
                                 Instant startedAt,
                                 CodexJsonlParser parser,
                                 BoundedTextBuffer stderr) {
            this.executionId = executionId;
            this.command = command;
            this.processSpec = processSpec;
            this.process = process;
            this.startedAt = startedAt;
            this.lastProgressAt = new AtomicReference<>(startedAt);
            this.parser = parser;
            this.stderr = stderr;
        }
    }

    private record TerminalDecision(
            String auditStatus,
            String diagnosticCode,
            String terminalEvent
    ) {
        private static TerminalDecision succeeded() {
            return new TerminalDecision("SUCCEEDED", "TURN_COMPLETED_EXIT_0", "turn.completed");
        }

        private static TerminalDecision failed(String code) {
            return new TerminalDecision("FAILED", bounded(code), "PROCESS_EXIT");
        }

        private static TerminalDecision unknown(String code, String event) {
            return new TerminalDecision("OUTCOME_UNKNOWN", bounded(code), event);
        }

        private static String bounded(String value) {
            return value.length() <= 100 ? value : value.substring(0, 100);
        }
    }
}
