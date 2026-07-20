CREATE TABLE deferred_task (
    id                   BIGSERIAL PRIMARY KEY,
    workspace_id         UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id   UUID         NOT NULL REFERENCES app_user (id),
    predecessor_task_id  BIGINT       NOT NULL REFERENCES task (id),
    title                VARCHAR(200) NOT NULL,
    description          TEXT,
    priority             VARCHAR(10)  NOT NULL,
    due_offset_minutes   INTEGER      NOT NULL DEFAULT 0,
    status               VARCHAR(20)  NOT NULL,
    created_task_id      BIGINT REFERENCES task (id),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    triggered_at         TIMESTAMPTZ,
    CONSTRAINT chk_deferred_task_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    CONSTRAINT chk_deferred_task_offset
        CHECK (due_offset_minutes BETWEEN 0 AND 10080),
    CONSTRAINT chk_deferred_task_status
        CHECK (status IN ('WAITING', 'TRIGGERED', 'CANCELED')),
    CONSTRAINT chk_deferred_task_trigger
        CHECK ((status = 'TRIGGERED' AND created_task_id IS NOT NULL AND triggered_at IS NOT NULL)
            OR (status <> 'TRIGGERED' AND created_task_id IS NULL AND triggered_at IS NULL))
);

CREATE INDEX idx_deferred_task_predecessor_status
    ON deferred_task (workspace_id, created_by_user_id, predecessor_task_id, status);

ALTER TABLE deferred_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE deferred_task FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_deferred_task_actor ON deferred_task
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
