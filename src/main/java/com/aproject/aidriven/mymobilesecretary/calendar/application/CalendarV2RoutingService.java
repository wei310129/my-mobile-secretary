package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CalendarV2RoutingService {

    private final CalendarV2RoutingProperties properties;

    public CalendarV2RoutingService(CalendarV2RoutingProperties properties) {
        this.properties = properties;
    }

    public boolean useCalendarV2() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            return false;
        }
        return properties.cutoverEnabled()
                || properties.pilotActorIds().contains(context.actorId());
    }

    /**
     * Global cutover is also the hard boundary for workspace-wide legacy background jobs.
     * Actor pilots continue to affect request routing only because those jobs cannot safely
     * partition shared-workspace legacy rows by the currently selected background actor.
     */
    public boolean globalCutoverEnabled() {
        return properties.cutoverEnabled();
    }
}
