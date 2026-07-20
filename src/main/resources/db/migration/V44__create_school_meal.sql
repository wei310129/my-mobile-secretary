CREATE TABLE school_meal (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    school_name VARCHAR(180) NOT NULL,
    normalized_school VARCHAR(180) NOT NULL,
    meal_date DATE NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    items_text VARCHAR(1200) NOT NULL,
    source_title VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_school_meal_actor_date_type
        UNIQUE (workspace_id, created_by_user_id, normalized_school, meal_date, meal_type)
);
CREATE INDEX idx_school_meal_actor_date
    ON school_meal (workspace_id, created_by_user_id, meal_date DESC);
ALTER TABLE school_meal ENABLE ROW LEVEL SECURITY;
ALTER TABLE school_meal FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_school_meal_actor ON school_meal FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
