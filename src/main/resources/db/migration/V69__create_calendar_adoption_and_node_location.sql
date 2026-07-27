ALTER TABLE calendar_time_node
    ADD COLUMN location_label VARCHAR(200),
    ADD COLUMN latitude DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD CONSTRAINT chk_calendar_node_location
        CHECK (
            (
                location_label IS NULL
                AND latitude IS NULL
                AND longitude IS NULL
            )
            OR (
                length(btrim(location_label)) BETWEEN 1 AND 200
                AND latitude BETWEEN -90 AND 90
                AND longitude BETWEEN -180 AND 180
            )
        );

CREATE TABLE calendar_adoption (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_adoption_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_adoption_plan_identity
        UNIQUE (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_adoption_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_adoption_status
        CHECK (status IN ('ACTIVE', 'CANCELED')),
    CONSTRAINT chk_calendar_adoption_revision CHECK (revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_adoption_active_plan
    ON calendar_adoption (plan_id, workspace_id, created_by_user_id)
    WHERE status = 'ACTIVE';

CREATE TABLE calendar_adoption_node (
    adoption_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    node_id UUID NOT NULL,
    node_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    PRIMARY KEY (
        adoption_id, node_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_adoption_node_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_adoption_node_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_adoption_node_revision
        CHECK (node_revision > 0)
);

CREATE INDEX idx_calendar_adoption_node_actor
    ON calendar_adoption_node (
        workspace_id, created_by_user_id, adoption_id, created_at);

ALTER TABLE calendar_adoption ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_adoption_actor ON calendar_adoption
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );

ALTER TABLE calendar_adoption_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_adoption_node_actor ON calendar_adoption_node
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
