package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CalendarReminderOccurrenceWorker {

    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarReminderOccurrenceProcessor processor;

    public CalendarReminderOccurrenceWorker(
            WorkspaceBackgroundRunner workspaceRunner,
            CalendarReminderOccurrenceProcessor processor) {
        this.workspaceRunner = workspaceRunner;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.calendar.reminder.poll-interval:30s}")
    public void poll() {
        workspaceRunner.forEachActor(
                "calendar-reminder-occurrence",
                context -> process(context.actorId()));
    }

    public void process(UUID targetUserId) {
        processor.process(targetUserId);
    }
}
