CREATE TABLE calendar_plan (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    placement_kind VARCHAR(20) NOT NULL,
    timed_start TIMESTAMPTZ,
    timed_end TIMESTAMPTZ,
    zone_id VARCHAR(64),
    all_day_start DATE,
    all_day_end_exclusive DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_plan_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_plan_title
        CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_plan_placement
        CHECK (
            (
                placement_kind = 'TIMED_INTERVAL'
                AND timed_start IS NOT NULL
                AND timed_end IS NOT NULL
                AND timed_end > timed_start
                AND zone_id IS NOT NULL
                AND all_day_start IS NULL
                AND all_day_end_exclusive IS NULL
            )
            OR (
                placement_kind = 'TIMED_POINT'
                AND timed_start IS NOT NULL
                AND timed_end IS NULL
                AND zone_id IS NOT NULL
                AND all_day_start IS NULL
                AND all_day_end_exclusive IS NULL
            )
            OR (
                placement_kind = 'ALL_DAY'
                AND timed_start IS NULL
                AND timed_end IS NULL
                AND zone_id IS NULL
                AND all_day_start IS NOT NULL
                AND all_day_end_exclusive > all_day_start
            )
        )
);

CREATE INDEX idx_calendar_plan_actor_updated
    ON calendar_plan (workspace_id, created_by_user_id, updated_at DESC);

CREATE TABLE calendar_activity (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    placement_kind VARCHAR(20) NOT NULL,
    timed_start TIMESTAMPTZ,
    timed_end TIMESTAMPTZ,
    zone_id VARCHAR(64),
    all_day_start DATE,
    all_day_end_exclusive DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_activity_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_activity_plan_identity
        UNIQUE (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_activity_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_activity_title
        CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_activity_placement
        CHECK (
            (
                placement_kind = 'TIMED_INTERVAL'
                AND timed_start IS NOT NULL
                AND timed_end IS NOT NULL
                AND timed_end > timed_start
                AND zone_id IS NOT NULL
                AND all_day_start IS NULL
                AND all_day_end_exclusive IS NULL
            )
            OR (
                placement_kind = 'TIMED_POINT'
                AND timed_start IS NOT NULL
                AND timed_end IS NULL
                AND zone_id IS NOT NULL
                AND all_day_start IS NULL
                AND all_day_end_exclusive IS NULL
            )
            OR (
                placement_kind = 'ALL_DAY'
                AND timed_start IS NULL
                AND timed_end IS NULL
                AND zone_id IS NULL
                AND all_day_start IS NOT NULL
                AND all_day_end_exclusive > all_day_start
            )
        )
);

CREATE INDEX idx_calendar_activity_plan
    ON calendar_activity (workspace_id, created_by_user_id, plan_id, created_at);

CREATE TABLE calendar_time_node (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_key VARCHAR(100) NOT NULL,
    label VARCHAR(200) NOT NULL,
    expression_kind VARCHAR(30) NOT NULL,
    absolute_time TIMESTAMPTZ,
    offset_seconds BIGINT,
    base_node_key VARCHAR(100),
    criticality VARCHAR(20) NOT NULL,
    adjustability VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_node_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_node_plan_identity
        UNIQUE (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_node_key
        UNIQUE (plan_id, workspace_id, created_by_user_id, node_key),
    CONSTRAINT fk_calendar_node_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_node_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_node_base
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id, base_node_key)
        REFERENCES calendar_time_node (
            plan_id, workspace_id, created_by_user_id, node_key),
    CONSTRAINT chk_calendar_node_key
        CHECK (length(btrim(node_key)) BETWEEN 1 AND 100),
    CONSTRAINT chk_calendar_node_label
        CHECK (length(btrim(label)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_node_criticality
        CHECK (criticality IN ('CRITICAL', 'NORMAL')),
    CONSTRAINT chk_calendar_node_adjustability
        CHECK (adjustability IN ('LOCKED', 'WINDOWED', 'FLEXIBLE')),
    CONSTRAINT chk_calendar_node_expression
        CHECK (
            (
                expression_kind = 'ABSOLUTE'
                AND absolute_time IS NOT NULL
                AND offset_seconds IS NULL
                AND base_node_key IS NULL
            )
            OR (
                expression_kind IN ('OWNER_START_OFFSET', 'OWNER_END_OFFSET')
                AND absolute_time IS NULL
                AND offset_seconds IS NOT NULL
                AND base_node_key IS NULL
            )
            OR (
                expression_kind = 'NODE_OFFSET'
                AND absolute_time IS NULL
                AND offset_seconds IS NOT NULL
                AND base_node_key IS NOT NULL
                AND base_node_key <> node_key
            )
        )
);

CREATE INDEX idx_calendar_node_plan_activity
    ON calendar_time_node (
        workspace_id, created_by_user_id, plan_id, activity_id, created_at);

ALTER TABLE calendar_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_plan_actor ON calendar_plan
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );

ALTER TABLE calendar_activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_activity FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_activity_actor ON calendar_activity
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );

ALTER TABLE calendar_time_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_time_node_actor ON calendar_time_node
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
