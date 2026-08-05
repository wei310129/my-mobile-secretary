-- Installation-owned reference data. This catalog is intentionally separate from the
-- workspace-owned place table and this migration must not contain production seed rows.

CREATE TABLE system_place_catalog (
    catalog_key           VARCHAR(120) PRIMARY KEY,
    logical_key           VARCHAR(120) NOT NULL,
    logical_name          VARCHAR(200) NOT NULL,
    normalized_logical_name VARCHAR(200) NOT NULL,
    point_name            VARCHAR(200) NOT NULL,
    normalized_point_name VARCHAR(200) NOT NULL,
    region                VARCHAR(100),
    address               VARCHAR(300),
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    category              VARCHAR(50),
    source_name           VARCHAR(120) NOT NULL,
    source_url            VARCHAR(500),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_system_place_catalog_coordinates
        CHECK ((latitude IS NULL AND longitude IS NULL)
            OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
    CONSTRAINT uq_system_place_catalog_logical_point
        UNIQUE (logical_key, catalog_key)
);

CREATE TABLE system_place_catalog_alias (
    id                 BIGSERIAL UNIQUE NOT NULL,
    catalog_key       VARCHAR(120) NOT NULL
        REFERENCES system_place_catalog (catalog_key) ON DELETE CASCADE,
    alias              VARCHAR(200) NOT NULL,
    normalized_alias   VARCHAR(200) NOT NULL,
    PRIMARY KEY (catalog_key, alias)
);

CREATE INDEX idx_system_place_catalog_logical_name
    ON system_place_catalog (normalized_logical_name)
    WHERE active;
CREATE INDEX idx_system_place_catalog_point_name
    ON system_place_catalog (normalized_point_name)
    WHERE active;
CREATE INDEX idx_system_place_catalog_region
    ON system_place_catalog (region)
    WHERE active;
CREATE INDEX idx_system_place_catalog_alias
    ON system_place_catalog_alias (normalized_alias);
