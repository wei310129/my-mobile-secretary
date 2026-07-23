CREATE TABLE project (
    id UUID PRIMARY KEY,
    project_type VARCHAR(30) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    creation_request_hmac VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_project_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_project_creation_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hmac),
    CONSTRAINT chk_project_type
        CHECK (project_type IN ('TRAVEL')),
    CONSTRAINT chk_project_name
        CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT chk_project_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT chk_project_creation_request_hmac
        CHECK (creation_request_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_project_lifecycle_timestamps
        CHECK (
            (status = 'ACTIVE' AND completed_at IS NULL AND archived_at IS NULL)
            OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND archived_at IS NULL)
            OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
        )
);

CREATE INDEX idx_project_actor_status_updated
    ON project (workspace_id, created_by_user_id, status, updated_at DESC);

ALTER TABLE project ENABLE ROW LEVEL SECURITY;
ALTER TABLE project FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_project_actor ON project
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
