package com.aproject.aidriven.mymobilesecretary.geo.catalog.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.domain.SystemPlaceCatalogEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read-only repository for installation-owned catalog rows. */
public interface SystemPlaceCatalogRepository extends JpaRepository<SystemPlaceCatalogEntry, String> {

    @Query("""
            select distinct entry from SystemPlaceCatalogEntry entry
            left join entry.aliases alias
            where entry.active = true
              and (entry.normalizedLogicalName = :query
                   or entry.normalizedPointName = :query
                   or alias.normalizedAlias = :query)
              and (:region is null or entry.region = :region)
            order by entry.logicalKey, entry.region, entry.catalogKey
            """)
    List<SystemPlaceCatalogEntry> findActiveMatches(
            @Param("query") String query, @Param("region") String region);
}
