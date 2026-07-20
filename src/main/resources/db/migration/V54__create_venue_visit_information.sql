CREATE TABLE venue_visit_information (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    venue_name          VARCHAR(160),
    normalized_venue    VARCHAR(160),
    subject             VARCHAR(200) NOT NULL,
    details             VARCHAR(1200) NOT NULL,
    reservation_required BOOLEAN     NOT NULL DEFAULT FALSE,
    minimum_group_size  INTEGER CHECK (minimum_group_size IS NULL OR minimum_group_size > 0),
    source_type         VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_venue_visit_source
        CHECK (source_type IN ('IMAGE', 'TEXT')),
    CONSTRAINT chk_venue_visit_status
        CHECK (status IN ('PENDING_VENUE', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT chk_venue_visit_active_has_venue
        CHECK (status <> 'ACTIVE' OR (venue_name IS NOT NULL AND normalized_venue IS NOT NULL))
);

CREATE INDEX idx_venue_visit_pending
    ON venue_visit_information (workspace_id, created_by_user_id, status, created_at DESC);

CREATE INDEX idx_venue_visit_lookup
    ON venue_visit_information
       (workspace_id, created_by_user_id, normalized_venue, status, updated_at DESC);

ALTER TABLE venue_visit_information ENABLE ROW LEVEL SECURITY;
ALTER TABLE venue_visit_information FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_venue_visit_information_actor ON venue_visit_information FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
