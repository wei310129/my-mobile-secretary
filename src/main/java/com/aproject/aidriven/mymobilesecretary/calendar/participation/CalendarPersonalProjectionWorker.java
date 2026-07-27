package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Actor-scoped worker entry point. A scheduler or delivery adapter must open
 * the recipient's authenticated workspace context before invoking it.
 */
@Component
public class CalendarPersonalProjectionWorker {

    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarPersonalProjectionProcessor processor;

    public CalendarPersonalProjectionWorker(
            WorkspaceBackgroundRunner workspaceRunner,
            CalendarPersonalProjectionProcessor processor) {
        this.workspaceRunner = workspaceRunner;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.calendar.personal-projection.poll-interval:30s}")
    public void poll() {
        workspaceRunner.forEachActor(
                "calendar-personal-projection",
                context -> process(context.actorId()));
    }

    public int processCurrentActor() {
        WorkspaceContext context =
                WorkspaceContextHolder.requireContext();
        return process(context.actorId());
    }

    public int process(UUID actorId) {
        return processor.process(actorId);
    }
}
