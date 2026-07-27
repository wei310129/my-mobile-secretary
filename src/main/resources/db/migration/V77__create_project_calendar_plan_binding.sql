CREATE TABLE project_calendar_plan_binding (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    calendar_plan_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    unlink_reason VARCHAR(30),
    binding_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    unlink_request_hash VARCHAR(64),
    unlink_payload_hash VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    unlinked_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_project_calendar_binding_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_project_calendar_binding_creation_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_project_calendar_binding_project
        FOREIGN KEY (project_id, workspace_id, created_by_user_id)
        REFERENCES project (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_project_calendar_binding_plan
        FOREIGN KEY (calendar_plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_project_calendar_binding_status
        CHECK (status IN ('ACTIVE', 'UNLINKED')),
    CONSTRAINT chk_project_calendar_binding_reason
        CHECK (
            unlink_reason IS NULL
            OR unlink_reason IN (
                'EXPLICIT', 'CALENDAR_CANCELED', 'CALENDAR_ARCHIVED')),
    CONSTRAINT chk_project_calendar_binding_lifecycle
        CHECK (
            (status = 'ACTIVE'
                AND unlink_reason IS NULL
                AND unlink_request_hash IS NULL
                AND unlink_payload_hash IS NULL
                AND unlinked_at IS NULL)
            OR (status = 'UNLINKED'
                AND unlink_reason IS NOT NULL
                AND unlinked_at IS NOT NULL
                AND (
                    (unlink_reason = 'EXPLICIT'
                        AND unlink_request_hash IS NOT NULL
                        AND unlink_payload_hash IS NOT NULL)
                    OR (unlink_reason <> 'EXPLICIT'
                        AND unlink_request_hash IS NULL
                        AND unlink_payload_hash IS NULL)))),
    CONSTRAINT chk_project_calendar_binding_revision
        CHECK (binding_revision > 0),
    CONSTRAINT chk_project_calendar_binding_hashes
        CHECK (
            creation_request_hash ~ '^[0-9a-f]{64}$'
            AND creation_payload_hash ~ '^[0-9a-f]{64}$'
            AND (
                unlink_request_hash IS NULL
                OR unlink_request_hash ~ '^[0-9a-f]{64}$')
            AND (
                unlink_payload_hash IS NULL
                OR unlink_payload_hash ~ '^[0-9a-f]{64}$'))
);

CREATE UNIQUE INDEX uq_project_calendar_binding_active_plan
    ON project_calendar_plan_binding (
        calendar_plan_id, workspace_id, created_by_user_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_project_calendar_binding_unlink_request
    ON project_calendar_plan_binding (
        workspace_id, created_by_user_id, unlink_request_hash)
    WHERE unlink_request_hash IS NOT NULL;

CREATE INDEX idx_project_calendar_binding_project
    ON project_calendar_plan_binding (
        workspace_id, created_by_user_id, project_id, status, updated_at DESC);

ALTER TABLE project_calendar_plan_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_calendar_plan_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_project_calendar_plan_binding_actor
    ON project_calendar_plan_binding FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
