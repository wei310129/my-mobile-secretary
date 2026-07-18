CREATE TABLE lifestyle_window (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID        NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID        NOT NULL REFERENCES app_user (id),
    day_type           VARCHAR(20) NOT NULL,
    kind               VARCHAR(20) NOT NULL,
    start_time         TIME        NOT NULL,
    end_time           TIME        NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_lifestyle_window_actor_day_kind
        UNIQUE (workspace_id, created_by_user_id, day_type, kind),
    CONSTRAINT chk_lifestyle_window_day_type CHECK (day_type IN ('WEEKDAY', 'HOLIDAY')),
    CONSTRAINT chk_lifestyle_window_kind CHECK (kind IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SLEEP')),
    CONSTRAINT chk_lifestyle_window_nonzero CHECK (start_time <> end_time)
);

CREATE INDEX idx_lifestyle_window_actor
    ON lifestyle_window (workspace_id, created_by_user_id, day_type, start_time);

ALTER TABLE lifestyle_window ENABLE ROW LEVEL SECURITY;
ALTER TABLE lifestyle_window FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_lifestyle_window_actor ON lifestyle_window
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
