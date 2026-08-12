package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.domain.ActorLocationKind;
import com.aproject.aidriven.mymobilesecretary.geo.domain.ActorLocationPreference;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.ActorLocationPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores actor-scoped typed locations without copying addresses or conversation text. */
@Service
@Transactional
public class ActorLocationPreferenceService {

    private final ActorLocationPreferenceRepository preferences;
    private final PlaceRepository places;
    private final Clock clock;

    public ActorLocationPreferenceService(
            ActorLocationPreferenceRepository preferences, PlaceRepository places, Clock clock) {
        this.preferences = preferences;
        this.places = places;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<HomeLocation> home() {
        return preference(ActorLocationKind.HOME)
                .filter(ActorLocationPreference::isActive)
                .map(preference -> confirmedPlace(preference.getPlaceId()))
                .map(HomeLocation::from);
    }

    public HomeLocation setHome(Long confirmedPlaceId) {
        Place place = confirmedPlace(confirmedPlaceId);
        Instant now = clock.instant();
        ActorLocationPreference preference = preference(ActorLocationKind.HOME)
                .orElseGet(() -> ActorLocationPreference.home(place.getId(), now));
        preference.activateAt(place.getId(), now);
        preferences.saveAndFlush(preference);
        return HomeLocation.from(place);
    }

    public void disableHome() {
        preference(ActorLocationKind.HOME).ifPresent(preference -> {
            preference.disable(clock.instant());
            preferences.saveAndFlush(preference);
        });
    }

    private Place confirmedPlace(Long placeId) {
        WorkspaceContext context = tenantContext();
        return places.findByIdAndWorkspaceIdAndCreatedByUserId(
                        placeId, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException("Place", placeId));
    }

    private Optional<ActorLocationPreference> preference(ActorLocationKind kind) {
        WorkspaceContext context = tenantContext();
        return preferences.findByWorkspaceIdAndCreatedByUserIdAndLocationKind(
                context.workspaceId(), context.actorId(), kind);
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Actor location preference requires tenant scope");
        }
        return context;
    }

    public record HomeLocation(
            Long placeId, String label, double latitude, double longitude) {

        private static HomeLocation from(Place place) {
            return new HomeLocation(
                    place.getId(), place.getName(), place.getLatitude(), place.getLongitude());
        }
    }
}
