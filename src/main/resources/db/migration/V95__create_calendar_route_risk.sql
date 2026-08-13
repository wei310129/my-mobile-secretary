CREATE TABLE calendar_route_risk (
    id UUID PRIMARY KEY,
    from_plan_id UUID NOT NULL,
    from_node_id UUID NOT NULL,
    from_source_created_by_user_id UUID NOT NULL,
    from_node_revision BIGINT NOT NULL,
    to_plan_id UUID NOT NULL,
    to_node_id UUID NOT NULL,
    to_source_created_by_user_id UUID NOT NULL,
    to_node_revision BIGINT NOT NULL,
    risk_kind VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    required_travel_seconds BIGINT NOT NULL,
    available_gap_seconds BIGINT NOT NULL,
    recommended_departure TIMESTAMPTZ NOT NULL,
    last_notified_required_seconds BIGINT NOT NULL,
    last_notified_departure TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_route_risk_actor_pair UNIQUE (
        workspace_id, created_by_user_id,
        from_plan_id, from_node_id, from_source_created_by_user_id,
        to_plan_id, to_node_id, to_source_created_by_user_id),
    CONSTRAINT fk_calendar_route_risk_from_node FOREIGN KEY (
        from_node_id, from_plan_id, workspace_id,
        from_source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_route_risk_to_node FOREIGN KEY (
        to_node_id, to_plan_id, workspace_id,
        to_source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_route_risk_kind CHECK (
        risk_kind IN ('IMPOSSIBLE', 'ALTERNATIVE_AVAILABLE')),
    CONSTRAINT chk_calendar_route_risk_status CHECK (
        (status = 'OPEN'
            AND confirmed_at IS NULL AND resolved_at IS NULL)
        OR (status = 'CONFIRMED'
            AND confirmed_at IS NOT NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED'
            AND resolved_at IS NOT NULL)),
    CONSTRAINT chk_calendar_route_risk_revisions CHECK (
        revision > 0
        AND from_node_revision > 0
        AND to_node_revision > 0),
    CONSTRAINT chk_calendar_route_risk_durations CHECK (
        required_travel_seconds > 0
        AND last_notified_required_seconds > 0)
);

CREATE INDEX idx_calendar_route_risk_actor_status
    ON calendar_route_risk (
        workspace_id, created_by_user_id, status, updated_at DESC);

ALTER TABLE calendar_route_risk ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_route_risk FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_route_risk_actor
    ON calendar_route_risk FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
