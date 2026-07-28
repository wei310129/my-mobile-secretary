package com.aproject.aidriven.mymobilesecretary.project.calendar;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ProjectCalendarPlanLifecycleListener {

    private final ProjectCalendarPlanBindingRepository bindings;

    public ProjectCalendarPlanLifecycleListener(
            ProjectCalendarPlanBindingRepository bindings) {
        this.bindings = bindings;
    }

    @EventListener
    public void onCalendarLifecycle(CalendarPlanLifecycleEvent event) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Project Calendar lifecycle requires a tenant workspace");
        }
        bindings.findWithLockByPlanIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                        event.planId(),
                        ProjectCalendarBindingStatus.ACTIVE,
                        context.workspaceId(),
                        context.actorId())
                .ifPresent(binding -> {
                    ProjectCalendarUnlinkReason reason =
                            event.action() == CalendarPlanLifecycleEvent.Action.CANCELED
                                    ? ProjectCalendarUnlinkReason.CALENDAR_CANCELED
                                    : ProjectCalendarUnlinkReason.CALENDAR_ARCHIVED;
                    binding.unlinkForCalendar(reason, event.occurredAt());
                    bindings.saveAndFlush(binding);
                });
    }
}
