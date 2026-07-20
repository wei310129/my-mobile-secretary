CREATE TABLE time_display_preference (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID        NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID        NOT NULL REFERENCES app_user (id),
    display_format     VARCHAR(24) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_time_display_preference_actor
        UNIQUE (workspace_id, created_by_user_id),
    CONSTRAINT ck_time_display_preference_format
        CHECK (display_format IN ('TWELVE_HOUR', 'TWENTY_FOUR_HOUR'))
);

ALTER TABLE time_display_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_display_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_time_display_preference_actor ON time_display_preference FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
