package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CalendarParticipationNotificationWorker {

    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarParticipationNotificationProcessor processor;

    public CalendarParticipationNotificationWorker(
            WorkspaceBackgroundRunner workspaceRunner,
            CalendarParticipationNotificationProcessor processor) {
        this.workspaceRunner = workspaceRunner;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.calendar.participation-notification.poll-interval:30s}")
    public void poll() {
        workspaceRunner.forEachActor(
                "calendar-participation-notification",
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
