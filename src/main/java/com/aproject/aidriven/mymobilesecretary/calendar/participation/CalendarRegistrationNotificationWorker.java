package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CalendarRegistrationNotificationWorker {

    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarRegistrationNotificationProcessor processor;

    public CalendarRegistrationNotificationWorker(
            WorkspaceBackgroundRunner workspaceRunner,
            CalendarRegistrationNotificationProcessor processor) {
        this.workspaceRunner = workspaceRunner;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.calendar.registration-notification.poll-interval:30s}")
    public void poll() {
        workspaceRunner.forEachActor(
                "calendar-registration-notification",
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
