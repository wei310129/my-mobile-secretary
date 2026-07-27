ALTER TABLE task
    ADD CONSTRAINT uq_task_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

CREATE TABLE calendar_task_binding (
    id UUID PRIMARY KEY,
    task_id BIGINT NOT NULL,
    target_kind VARCHAR(20) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_id UUID,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_task_binding_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_task_binding_task
        UNIQUE (task_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_task_binding_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_calendar_task_binding_task
        FOREIGN KEY (task_id, workspace_id, created_by_user_id)
        REFERENCES task (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_task_binding_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_task_binding_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_task_binding_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_task_binding_target
        CHECK (
            (target_kind = 'PLAN' AND activity_id IS NULL AND node_id IS NULL)
            OR (target_kind = 'ACTIVITY' AND activity_id IS NOT NULL AND node_id IS NULL)
            OR (target_kind = 'NODE' AND activity_id IS NULL AND node_id IS NOT NULL)
        ),
    CONSTRAINT chk_calendar_task_binding_request_hash
        CHECK (creation_request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_task_binding_payload_hash
        CHECK (creation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_calendar_task_binding_target
    ON calendar_task_binding (
        workspace_id, created_by_user_id, target_kind, plan_id, activity_id, node_id);

ALTER TABLE calendar_task_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_task_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_task_binding_actor ON calendar_task_binding
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
