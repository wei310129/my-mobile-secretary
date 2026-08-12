CREATE TABLE route_place_creation_draft (
    id UUID PRIMARY KEY,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    parent_calendar_draft_id UUID NOT NULL,
    parent_calendar_draft_revision BIGINT NOT NULL,
    requested_alias VARCHAR(80) NOT NULL,
    place_query VARCHAR(300) NOT NULL,
    candidate_name VARCHAR(200) NOT NULL,
    candidate_address VARCHAR(500),
    candidate_latitude DOUBLE PRECISION NOT NULL,
    candidate_longitude DOUBLE PRECISION NOT NULL,
    candidate_type VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_route_place_creation_parent
        FOREIGN KEY (parent_calendar_draft_id) REFERENCES calendar_intent_draft(id),
    CONSTRAINT chk_route_place_creation_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$' AND scope_key_version > 0),
    CONSTRAINT chk_route_place_creation_parent_revision CHECK (
        parent_calendar_draft_revision > 0),
    CONSTRAINT chk_route_place_creation_text CHECK (
        length(btrim(requested_alias)) BETWEEN 1 AND 80
        AND length(btrim(place_query)) BETWEEN 2 AND 300
        AND length(btrim(candidate_name)) BETWEEN 1 AND 200),
    CONSTRAINT chk_route_place_creation_coordinate CHECK (
        candidate_latitude BETWEEN -90 AND 90
        AND candidate_longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_route_place_creation_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_route_place_creation_revision CHECK (revision > 0),
    CONSTRAINT chk_route_place_creation_expiry CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uq_route_place_creation_pending_scope
    ON route_place_creation_draft (
        workspace_id, created_by_user_id, channel, conversation_scope_digest)
    WHERE status = 'PENDING';

ALTER TABLE route_place_creation_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE route_place_creation_draft FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_route_place_creation_actor
    ON route_place_creation_draft FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
