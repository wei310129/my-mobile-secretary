-- Personal packing memory. Preferences stay private to the actor even inside a household
-- workspace because health, body, and activity-related packing choices may be sensitive.
CREATE TABLE travel_packing_preference (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    item_name          VARCHAR(100) NOT NULL,
    normalized_item    VARCHAR(100) NOT NULL,
    preference         VARCHAR(20)  NOT NULL,
    reason             VARCHAR(300),
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_travel_packing_preference_actor_item
        UNIQUE (workspace_id, created_by_user_id, normalized_item),
    CONSTRAINT chk_travel_packing_preference_value
        CHECK (preference IN ('ALWAYS_SUGGEST', 'NEVER_SUGGEST'))
);

CREATE INDEX idx_travel_packing_preference_actor
    ON travel_packing_preference (workspace_id, created_by_user_id, item_name);

ALTER TABLE travel_packing_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE travel_packing_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_travel_packing_preference_actor ON travel_packing_preference
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
