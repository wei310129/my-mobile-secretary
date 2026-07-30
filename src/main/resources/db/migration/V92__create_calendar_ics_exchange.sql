CREATE TABLE calendar_ics_export_artifact (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    window_start DATE NOT NULL,
    window_end_exclusive DATE NOT NULL,
    export_profile VARCHAR(20) NOT NULL,
    loss_report TEXT NOT NULL,
    storage_key VARCHAR(80) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    artifact_status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_ics_export_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_ics_export_token UNIQUE (token_hash),
    CONSTRAINT uq_calendar_ics_export_request
        UNIQUE (workspace_id, created_by_user_id, operation_request_hash),
    CONSTRAINT chk_calendar_ics_export_profile
        CHECK (export_profile IN ('COMPACT', 'ROUTE_AWARE')),
    CONSTRAINT chk_calendar_ics_export_window
        CHECK (window_end_exclusive > window_start
            AND window_end_exclusive <= window_start + 366),
    CONSTRAINT chk_calendar_ics_export_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT chk_calendar_ics_export_hashes
        CHECK (content_hash ~ '^[0-9a-f]{64}$'
            AND token_hash ~ '^[0-9a-f]{64}$'
            AND operation_request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_ics_export_status
        CHECK (artifact_status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_calendar_ics_export_lifecycle CHECK (
        (artifact_status = 'ACTIVE'
            AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (artifact_status = 'USED'
            AND consumed_at IS NOT NULL AND revoked_at IS NULL)
        OR (artifact_status = 'REVOKED'
            AND consumed_at IS NULL AND revoked_at IS NOT NULL)
        OR (artifact_status = 'EXPIRED'
            AND consumed_at IS NULL))
);

ALTER TABLE calendar_ics_export_artifact ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_ics_export_artifact FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_ics_export_actor
    ON calendar_ics_export_artifact FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
