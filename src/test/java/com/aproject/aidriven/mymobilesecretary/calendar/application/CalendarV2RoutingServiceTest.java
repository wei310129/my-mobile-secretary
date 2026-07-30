package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarV2RoutingServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Test
    void nonTenantScopeFailsClosedEvenWhenCutoverIsEnabled() {
        CalendarV2RoutingService routing =
                new CalendarV2RoutingService(new CalendarV2RoutingProperties(true, Set.of()));

        try (var ignored = WorkspaceContextHolder.open(WorkspaceContext.system())) {
            assertThat(routing.useCalendarV2()).isFalse();
        }
    }

    @Test
    void ordinaryActorStaysOnLegacyWhileCutoverIsDisabled() {
        CalendarV2RoutingService routing =
                new CalendarV2RoutingService(new CalendarV2RoutingProperties(false, Set.of()));

        assertThat(inTenant(routing)).isFalse();
    }

    @Test
    void configuredPilotActorUsesCalendarV2() {
        CalendarV2RoutingService routing =
                new CalendarV2RoutingService(new CalendarV2RoutingProperties(false, Set.of(ACTOR)));

        assertThat(inTenant(routing)).isTrue();
    }

    @Test
    void globalCutoverRoutesTenantActorToCalendarV2() {
        CalendarV2RoutingService routing =
                new CalendarV2RoutingService(new CalendarV2RoutingProperties(true, Set.of()));

        assertThat(inTenant(routing)).isTrue();
    }

    private static boolean inTenant(CalendarV2RoutingService routing) {
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST))) {
            return routing.useCalendarV2();
        }
    }
}
