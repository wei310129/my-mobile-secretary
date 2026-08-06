package com.aproject.aidriven.mymobilesecretary.geo.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Normalized lookup alias for a system catalog point. */
@Entity
@Table(name = "system_place_catalog_alias")
public class SystemPlaceCatalogAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_key", nullable = false)
    private SystemPlaceCatalogEntry catalogEntry;

    @Column(nullable = false, length = 200)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, length = 200)
    private String normalizedAlias;

    protected SystemPlaceCatalogAlias() {
    }

    static SystemPlaceCatalogAlias create(SystemPlaceCatalogEntry entry, String alias) {
        SystemPlaceCatalogAlias result = new SystemPlaceCatalogAlias();
        result.catalogEntry = entry;
        result.alias = alias.strip();
        result.normalizedAlias = SystemPlaceCatalogEntry.normalize(alias);
        return result;
    }

    public String getAlias() { return alias; }
}
