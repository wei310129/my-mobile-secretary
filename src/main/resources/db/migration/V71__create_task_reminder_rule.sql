CREATE TABLE task_reminder_rule (
    id UUID PRIMARY KEY,
    task_id BIGINT NOT NULL,
    remind_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_task_reminder_rule_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_task_reminder_rule_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_task_reminder_rule_task
        FOREIGN KEY (task_id, workspace_id, created_by_user_id)
        REFERENCES task (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_task_reminder_rule_status
        CHECK (status IN ('ACTIVE', 'TRIGGERED', 'CANCELED')),
    CONSTRAINT chk_task_reminder_rule_revision
        CHECK (revision > 0),
    CONSTRAINT chk_task_reminder_rule_request_hash
        CHECK (creation_request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_task_reminder_rule_payload_hash
        CHECK (creation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_task_reminder_rule_active_task
    ON task_reminder_rule (task_id, workspace_id, created_by_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_task_reminder_rule_due
    ON task_reminder_rule (
        workspace_id, created_by_user_id, status, remind_at);

ALTER TABLE task_reminder_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_reminder_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_task_reminder_rule_actor ON task_reminder_rule
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
