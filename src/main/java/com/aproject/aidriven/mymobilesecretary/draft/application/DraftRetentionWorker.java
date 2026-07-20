package com.aproject.aidriven.mymobilesecretary.draft.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Actor-isolated expiry warnings and midnight cleanup for seven-day drafts. */
@Component
public class DraftRetentionWorker {
    private final DraftRetentionService retentionService;
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final Clock clock;

    public DraftRetentionWorker(DraftRetentionService retentionService,
                                WorkspaceBackgroundRunner workspaceRunner, Clock clock) {
        this.retentionService = retentionService;
        this.workspaceRunner = workspaceRunner;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void warnOnExpiryDay() {
        Instant now = Instant.now(clock);
        workspaceRunner.forEachActor("seven-day-draft-expiry-warning",
                ignored -> retentionService.notifyExpiringDrafts(now));
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Taipei")
    public void deleteAtMidnight() {
        Instant now = Instant.now(clock);
        workspaceRunner.forEachActor("seven-day-draft-midnight-cleanup",
                ignored -> retentionService.deleteExpiredDrafts(now));
    }
}
