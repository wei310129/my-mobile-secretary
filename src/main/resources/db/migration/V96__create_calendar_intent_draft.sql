CREATE TABLE calendar_intent_draft (
    id UUID PRIMARY KEY,
    channel VARCHAR(30) NOT NULL,
    conversation_scope_digest CHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    placement_kind VARCHAR(20) NOT NULL,
    timed_start TIMESTAMPTZ,
    timed_end TIMESTAMPTZ,
    zone_id VARCHAR(64),
    all_day_start DATE,
    all_day_end_exclusive DATE,
    category VARCHAR(100),
    location_label VARCHAR(200),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    confirmation_hash CHAR(64),
    materialized_plan_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_intent_draft_actor_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_intent_draft_request
        UNIQUE (workspace_id, created_by_user_id, request_hash),
    CONSTRAINT uq_calendar_intent_draft_confirmation
        UNIQUE (workspace_id, created_by_user_id, confirmation_hash),
    CONSTRAINT fk_calendar_intent_draft_plan FOREIGN KEY (
        materialized_plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_intent_draft_title
        CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_intent_draft_status CHECK (
        (status = 'PENDING'
            AND confirmation_hash IS NULL
            AND materialized_plan_id IS NULL)
        OR (status = 'MATERIALIZED'
            AND confirmation_hash IS NOT NULL
            AND materialized_plan_id IS NOT NULL)
        OR (status = 'DISCARDED'
            AND materialized_plan_id IS NULL)),
    CONSTRAINT chk_calendar_intent_draft_revision CHECK (revision > 0),
    CONSTRAINT chk_calendar_intent_draft_scope CHECK (
        length(btrim(channel)) BETWEEN 1 AND 30
        AND conversation_scope_digest ~ '^[0-9a-f]{64}$'
        AND scope_key_version > 0),
    CONSTRAINT chk_calendar_intent_draft_placement CHECK (
        (placement_kind = 'TIMED_INTERVAL'
            AND timed_start IS NOT NULL
            AND timed_end IS NOT NULL
            AND timed_end > timed_start
            AND zone_id IS NOT NULL
            AND all_day_start IS NULL
            AND all_day_end_exclusive IS NULL)
        OR (placement_kind = 'TIMED_POINT'
            AND timed_start IS NOT NULL
            AND timed_end IS NULL
            AND zone_id IS NOT NULL
            AND all_day_start IS NULL
            AND all_day_end_exclusive IS NULL)
        OR (placement_kind = 'ALL_DAY'
            AND timed_start IS NULL
            AND timed_end IS NULL
            AND zone_id IS NULL
            AND all_day_start IS NOT NULL
            AND all_day_end_exclusive > all_day_start)),
    CONSTRAINT chk_calendar_intent_draft_location CHECK (
        (location_label IS NULL
            AND latitude IS NULL
            AND longitude IS NULL)
        OR (length(btrim(location_label)) BETWEEN 1 AND 200
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180))
);

CREATE INDEX idx_calendar_intent_draft_scope
    ON calendar_intent_draft (
        workspace_id, created_by_user_id, channel,
        conversation_scope_digest, status, updated_at DESC);

ALTER TABLE calendar_intent_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_intent_draft FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_intent_draft_actor
    ON calendar_intent_draft FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
