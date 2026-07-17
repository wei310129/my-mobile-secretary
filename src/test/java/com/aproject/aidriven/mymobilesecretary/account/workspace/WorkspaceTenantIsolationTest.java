package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import com.aproject.aidriven.mymobilesecretary.account.domain.Workspace;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceRepository;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PlanningPreference;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PlanningPreferenceRepository;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkspaceTenantIsolationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Autowired private AppUserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private PlanningPreferenceRepository preferenceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void hibernateDiscriminatorAndSingletonsIsolateTwoWorkspaces() {
        ActorWorkspace first = createActorWorkspace("First household");
        ActorWorkspace second = createActorWorkspace("Second household");

        Place firstPlace = in(first.context(), () -> placeRepository.save(
                Place.create("Shared shop", "First address", 25.03, 121.56, "SHOP", NOW)));
        Place secondPlace = in(second.context(), () -> placeRepository.save(
                Place.create("Shared shop", "Second address", 25.04, 121.57, "SHOP", NOW)));

        Item firstItem = in(first.context(), () -> itemRepository.save(
                Item.create("Shared item", Set.of(firstPlace.getId()), NOW)));
        Item secondItem = in(second.context(), () -> itemRepository.save(
                Item.create("Shared item", Set.of(secondPlace.getId()), NOW)));

        PlanningPreference firstPreference = in(first.context(), () -> savePreference(5));
        PlanningPreference secondPreference = in(second.context(), () -> savePreference(15));
        List<Item> firstItems = in(first.context(), () -> itemRepository.findAll());
        List<Item> secondItems = in(second.context(), () -> itemRepository.findAll());

        assertThat(firstPreference.getId()).isNotEqualTo(secondPreference.getId());
        assertThat(firstItems).extracting(Item::getId)
                .containsExactly(firstItem.getId());
        assertThat(secondItems).extracting(Item::getId)
                .containsExactly(secondItem.getId());
        assertThat(in(second.context(), () -> itemRepository.findById(firstItem.getId()))).isEmpty();
        assertThat(in(first.context(), () -> placeRepository.findWithinRadius(25.03, 121.56, 5000)))
                .extracting(Place::getId).containsExactly(firstPlace.getId());
        assertThat(in(second.context(), () -> placeRepository.findWithinRadius(25.03, 121.56, 5000)))
                .extracting(Place::getId).containsExactly(secondPlace.getId());
        assertThat(in(first.context(), () -> preferenceRepository.findFirstByOrderByIdAsc()))
                .get().extracting(PlanningPreference::getExtraTransferMinutes).isEqualTo(5);
        assertThat(in(second.context(), () -> preferenceRepository.findFirstByOrderByIdAsc()))
                .get().extracting(PlanningPreference::getExtraTransferMinutes).isEqualTo(15);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT workspace_id FROM item_place WHERE item_id = ?",
                UUID.class, firstItem.getId())).isEqualTo(first.workspaceId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM item_place WHERE item_id = ?",
                UUID.class, firstItem.getId())).isEqualTo(first.actorId());

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.openAuthentication()) {
            assertThat(userRepository.findById(first.actorId())).isPresent();
            assertThat(itemRepository.findAll()).isEmpty();
        }
    }

    private PlanningPreference savePreference(int transferMinutes) {
        PlanningPreference preference = PlanningPreference.create(NOW);
        preference.setBuffers(transferMinutes, 0, NOW);
        return preferenceRepository.save(preference);
    }

    private ActorWorkspace createActorWorkspace(String name) {
        AppUser actor = userRepository.save(AppUser.create(name + " owner", NOW));
        Workspace workspace = workspaceRepository.save(
                Workspace.createHousehold(actor.getId(), name, NOW));
        return new ActorWorkspace(actor.getId(), workspace.getId());
    }

    private static <T> T in(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record ActorWorkspace(UUID actorId, UUID workspaceId) {
        WorkspaceContext context() {
            return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
        }
    }
}
