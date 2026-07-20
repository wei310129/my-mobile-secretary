package com.aproject.aidriven.mymobilesecretary.safety.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Default verification after the user leaves the confirmation question unanswered. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WorkSchoolSuspensionVerificationWorker {
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final WorkSchoolSuspensionService service;

    public WorkSchoolSuspensionVerificationWorker(WorkspaceBackgroundRunner workspaceRunner,
                                                   WorkSchoolSuspensionService service) {
        this.workspaceRunner = workspaceRunner;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.suspension.verification-poll-ms:60000}")
    public void verifyDue() {
        workspaceRunner.forEachActor("suspension-official-verification",
                ignored -> service.verifyDue());
    }
}
