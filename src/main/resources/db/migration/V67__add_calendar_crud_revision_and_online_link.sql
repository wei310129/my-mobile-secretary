ALTER TABLE calendar_plan
    ADD COLUMN creation_request_hash VARCHAR(64),
    ADD COLUMN creation_payload_hash VARCHAR(64),
    ADD COLUMN category VARCHAR(80);

ALTER TABLE calendar_plan
    ADD CONSTRAINT chk_calendar_plan_request_hash
        CHECK (
            creation_request_hash IS NULL
            OR creation_request_hash ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_calendar_plan_payload_hash
        CHECK (
            creation_payload_hash IS NULL
            OR creation_payload_hash ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_calendar_plan_category
        CHECK (category IS NULL OR length(category) BETWEEN 1 AND 80);

CREATE UNIQUE INDEX uq_calendar_plan_creation_request
    ON calendar_plan (workspace_id, created_by_user_id, creation_request_hash)
    WHERE creation_request_hash IS NOT NULL;

ALTER TABLE calendar_activity
    ADD COLUMN category VARCHAR(80),
    ADD CONSTRAINT chk_calendar_activity_category
        CHECK (category IS NULL OR length(category) BETWEEN 1 AND 80);

ALTER TABLE calendar_time_node
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_calendar_node_revision CHECK (revision > 0);

CREATE TABLE calendar_online_access_link (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_id UUID,
    normalized_uri TEXT NOT NULL,
    safe_host VARCHAR(253) NOT NULL,
    label VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_link_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_link_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_link_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_link_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_link_target
        CHECK (
            (activity_id IS NULL AND node_id IS NULL)
            OR (activity_id IS NOT NULL AND node_id IS NULL)
            OR (activity_id IS NULL AND node_id IS NOT NULL)
        ),
    CONSTRAINT chk_calendar_link_https
        CHECK (normalized_uri ~ '^https://'),
    CONSTRAINT chk_calendar_link_host
        CHECK (length(btrim(safe_host)) BETWEEN 1 AND 253),
    CONSTRAINT chk_calendar_link_label
        CHECK (label IS NULL OR length(btrim(label)) BETWEEN 1 AND 120)
);

CREATE UNIQUE INDEX uq_calendar_link_plan_target
    ON calendar_online_access_link (
        plan_id, workspace_id, created_by_user_id)
    WHERE activity_id IS NULL AND node_id IS NULL;

CREATE UNIQUE INDEX uq_calendar_link_activity_target
    ON calendar_online_access_link (
        activity_id, workspace_id, created_by_user_id)
    WHERE activity_id IS NOT NULL AND node_id IS NULL;

CREATE UNIQUE INDEX uq_calendar_link_node_target
    ON calendar_online_access_link (
        node_id, workspace_id, created_by_user_id)
    WHERE node_id IS NOT NULL;

ALTER TABLE calendar_online_access_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_online_access_link FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_online_link_actor ON calendar_online_access_link
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
