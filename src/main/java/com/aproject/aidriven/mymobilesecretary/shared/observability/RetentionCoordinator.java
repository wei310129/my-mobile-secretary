package com.aproject.aidriven.mymobilesecretary.shared.observability;

import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentTraceRetentionService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs privacy retention independently so one failing store cannot block the remaining purges. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RetentionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RetentionCoordinator.class);

    private final IntentTraceRetentionService traceRetentionService;
    private final LineMessageLogService lineMessageLogService;
    private final SecurityAuditService securityAuditService;
    private final IdempotencyService idempotencyService;
    private final WorkspaceBackgroundRunner workspaceRunner;

    public RetentionCoordinator(IntentTraceRetentionService traceRetentionService,
                                LineMessageLogService lineMessageLogService,
                                SecurityAuditService securityAuditService,
                                IdempotencyService idempotencyService,
                                WorkspaceBackgroundRunner workspaceRunner) {
        this.traceRetentionService = traceRetentionService;
        this.lineMessageLogService = lineMessageLogService;
        this.securityAuditService = securityAuditService;
        this.idempotencyService = idempotencyService;
        this.workspaceRunner = workspaceRunner;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 17 3 * * *}",
            zone = "${app.retention.cleanup-zone:Asia/Taipei}")
    public void runScheduledCleanup() {
        cleanup();
    }

    public CleanupResult cleanup() {
        int rawTracesCleared = -1;
        int traceSummariesDeleted = -1;
        try {
            IntentTraceRetentionService.PurgeResult result = workspaceRunner.runSystem(
                    traceRetentionService::purgeExpired);
            rawTracesCleared = result.rawPayloadsCleared();
            traceSummariesDeleted = result.summariesDeleted();
        } catch (Exception exception) {
            log.warn("Retention purge failed [store=intent-trace, cause={}]",
                    exception.getClass().getSimpleName());
        }

        long lineMessagesDeleted = purgeLineMessages();
        long securityAuditsDeleted = purge("security-audit",
                () -> workspaceRunner.runSystem(securityAuditService::purgeExpired));
        long idempotencyRecordsDeleted = purge("idempotency",
                () -> workspaceRunner.runSystem(idempotencyService::purgeExpired));
        CleanupResult result = new CleanupResult(rawTracesCleared, traceSummariesDeleted,
                lineMessagesDeleted, securityAuditsDeleted, idempotencyRecordsDeleted);
        log.info("Retention cleanup completed [rawTraces={}, traceSummaries={}, lineMessages={}, "
                        + "securityAudits={}, idempotencyRecords={}]",
                result.rawTracesCleared(), result.traceSummariesDeleted(),
                result.lineMessagesDeleted(), result.securityAuditsDeleted(),
                result.idempotencyRecordsDeleted());
        return result;
    }

    private long purgeLineMessages() {
        AtomicLong deleted = new AtomicLong();
        try {
            workspaceRunner.forEachWorkspace("line-message-retention", ignored ->
                    deleted.addAndGet(lineMessageLogService.purgeExpired()));
            return deleted.get();
        } catch (Exception exception) {
            log.warn("Retention purge failed [store=line-message, cause={}]",
                    exception.getClass().getSimpleName());
            return -1;
        }
    }

    private long purge(String store, LongSupplier operation) {
        try {
            return operation.getAsLong();
        } catch (Exception exception) {
            log.warn("Retention purge failed [store={}, cause={}]",
                    store, exception.getClass().getSimpleName());
            return -1;
        }
    }

    public record CleanupResult(int rawTracesCleared, int traceSummariesDeleted,
                                long lineMessagesDeleted, long securityAuditsDeleted,
                                long idempotencyRecordsDeleted) {
    }
}
