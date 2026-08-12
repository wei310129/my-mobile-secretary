package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.ActorRouteOperationPreference;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.ActorRouteOperationPreferenceRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable actor defaults for typed route operations. */
@Service
@Transactional
public class RouteOperationPreferenceService {

    private final ActorRouteOperationPreferenceRepository preferences;
    private final Clock clock;

    public RouteOperationPreferenceService(
            ActorRouteOperationPreferenceRepository preferences, Clock clock) {
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<View> current() {
        return preference().filter(ActorRouteOperationPreference::isActive).map(View::from);
    }

    public View setGeneral(int beforeMinutes, int afterMinutes) {
        ActorRouteOperationPreference preference = getOrCreate();
        preference.setGeneral(beforeMinutes, afterMinutes, clock.instant());
        return View.from(preferences.saveAndFlush(preference));
    }

    public View setParking(int minutes) {
        ActorRouteOperationPreference preference = getOrCreate();
        preference.setParking(minutes, clock.instant());
        return View.from(preferences.saveAndFlush(preference));
    }

    public View setRideHailWait(int minutes) {
        ActorRouteOperationPreference preference = getOrCreate();
        preference.setRideHailWait(minutes, clock.instant());
        return View.from(preferences.saveAndFlush(preference));
    }

    private ActorRouteOperationPreference getOrCreate() {
        return preference().orElseGet(() -> ActorRouteOperationPreference.create(clock.instant()));
    }

    private Optional<ActorRouteOperationPreference> preference() {
        WorkspaceContext context = tenantContext();
        return preferences.findByWorkspaceIdAndCreatedByUserId(
                context.workspaceId(), context.actorId());
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Route operation preference requires tenant scope");
        }
        return context;
    }

    public record View(
            Integer generalBeforeMinutes,
            Integer generalAfterMinutes,
            Integer parkingMinutes,
            Integer rideHailWaitMinutes,
            long revision) {

        private static View from(ActorRouteOperationPreference preference) {
            return new View(
                    preference.getGeneralBeforeMinutes(),
                    preference.getGeneralAfterMinutes(),
                    preference.getParkingMinutes(),
                    preference.getRideHailWaitMinutes(),
                    preference.getRevision());
        }

        public boolean hasGeneral() {
            return generalBeforeMinutes != null && generalAfterMinutes != null;
        }
    }
}
