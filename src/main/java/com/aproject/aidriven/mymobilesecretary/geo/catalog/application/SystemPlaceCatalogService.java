package com.aproject.aidriven.mymobilesecretary.geo.catalog.application;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.domain.SystemPlaceCatalogEntry;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.persistence.SystemPlaceCatalogRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Query and selection boundary for installation-owned place reference data. */
@Service
@Transactional(readOnly = true)
public class SystemPlaceCatalogService {

    private final SystemPlaceCatalogRepository repository;

    public SystemPlaceCatalogService(SystemPlaceCatalogRepository repository) {
        this.repository = repository;
    }

    public SystemPlaceCatalogLookup lookup(String query, String region) {
        String normalizedQuery = SystemPlaceCatalogEntry.normalize(query);
        String normalizedRegion = clean(region);
        List<SystemPlaceCatalogView> candidates = repository
                .findActiveMatches(normalizedQuery, normalizedRegion).stream()
                .map(SystemPlaceCatalogService::view)
                .sorted(Comparator.comparing(SystemPlaceCatalogView::logicalKey)
                        .thenComparing(view -> Objects.toString(view.region(), ""))
                        .thenComparing(SystemPlaceCatalogView::catalogKey))
                .toList();
        return new SystemPlaceCatalogLookup(
                status(candidates), query.strip(), normalizedRegion, candidates);
    }

    public SystemPlaceCatalogAdoption adopt(String catalogKey) {
        SystemPlaceCatalogEntry entry = repository.findById(catalogKey)
                .filter(SystemPlaceCatalogEntry::isActive)
                .orElseThrow(() -> new NotFoundException("SystemPlaceCatalog", catalogKey));
        return new SystemPlaceCatalogAdoption(view(entry), false, false);
    }

    public SystemPlaceCatalogAdoption adopt(String query, String region, Integer ordinal) {
        SystemPlaceCatalogLookup lookup = lookup(query, region);
        if (lookup.status() == SystemPlaceCatalogLookup.Status.NOT_FOUND) {
            throw new NotFoundException("SystemPlaceCatalog", query);
        }
        int index = ordinal == null ? 0 : ordinal - 1;
        if (index < 0 || index >= lookup.candidates().size()
                || (ordinal == null && lookup.status() != SystemPlaceCatalogLookup.Status.EXACT)) {
            throw new IllegalArgumentException("system catalog selection is ambiguous");
        }
        return adopt(lookup.candidates().get(index).catalogKey());
    }

    private static SystemPlaceCatalogLookup.Status status(List<SystemPlaceCatalogView> candidates) {
        if (candidates.isEmpty()) {
            return SystemPlaceCatalogLookup.Status.NOT_FOUND;
        }
        long logicalCount = candidates.stream()
                .map(SystemPlaceCatalogView::logicalKey).distinct().count();
        long regionCount = candidates.stream()
                .map(view -> clean(view.region())).distinct().count();
        if (logicalCount == 1 && candidates.size() == 1) {
            return SystemPlaceCatalogLookup.Status.EXACT;
        }
        if (logicalCount == 1) {
            return SystemPlaceCatalogLookup.Status.MULTIPOINT;
        }
        if (regionCount > 1) {
            return SystemPlaceCatalogLookup.Status.CROSS_REGION;
        }
        return SystemPlaceCatalogLookup.Status.MULTIPLE;
    }

    private static SystemPlaceCatalogView view(SystemPlaceCatalogEntry entry) {
        return new SystemPlaceCatalogView(
                entry.getCatalogKey(), entry.getLogicalKey(), entry.getLogicalName(),
                entry.getPointName(), entry.getRegion(), entry.getAddress(),
                entry.getLatitude(), entry.getLongitude(), entry.getCategory(),
                entry.getSourceName(), entry.getSourceUrl());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
