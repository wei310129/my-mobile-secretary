package com.aproject.aidriven.mymobilesecretary.travel.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference.Preference;
import com.aproject.aidriven.mymobilesecretary.travel.persistence.TravelPackingPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistent, actor-private packing memory. One-off omissions are intentionally not saved here. */
@Service
@Transactional
public class TravelPackingPreferenceService {

    private final TravelPackingPreferenceRepository repository;
    private final Clock clock;

    public TravelPackingPreferenceService(TravelPackingPreferenceRepository repository,
                                          Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public TravelPackingPreference remember(String itemName, Preference preference, String reason) {
        String displayName = requireItem(itemName);
        String normalized = normalize(displayName);
        String safeReason = trimToNull(reason, 300);
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        TravelPackingPreference value = repository
                .findByCreatedByUserIdAndNormalizedItem(actorId, normalized)
                .orElseGet(() -> TravelPackingPreference.create(
                        displayName, normalized, preference, safeReason, Instant.now(clock)));
        value.update(displayName, normalized, preference, safeReason, Instant.now(clock));
        return repository.save(value);
    }

    public boolean forget(String itemName) {
        String normalized = normalize(requireItem(itemName));
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        return repository.findByCreatedByUserIdAndNormalizedItem(actorId, normalized)
                .map(value -> {
                    repository.delete(value);
                    return true;
                }).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<TravelPackingPreference> list() {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        return repository.findByCreatedByUserIdOrderByItemNameAsc(actorId);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s　]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String requireItem(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("packing preference missing item");
        }
        String stripped = value.strip();
        if (stripped.length() > 100) {
            throw new IllegalArgumentException("packing preference item too long");
        }
        return stripped;
    }

    private static String trimToNull(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }
}
