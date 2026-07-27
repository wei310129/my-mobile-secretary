ALTER TABLE calendar_time_node
    ADD COLUMN resolved_time TIMESTAMPTZ;

-- V66 RLS hides existing actor rows while Flyway has no request tenant context.
-- This migration is transactional: suspend RLS only for the bounded backfill,
-- then restore ENABLE + FORCE before creating the new reminder objects.
ALTER TABLE calendar_time_node NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node DISABLE ROW LEVEL SECURITY;

UPDATE calendar_time_node
SET resolved_time = absolute_time
WHERE expression_kind = 'ABSOLUTE';

UPDATE calendar_time_node node
SET resolved_time = CASE node.expression_kind
        WHEN 'OWNER_START_OFFSET' THEN
            COALESCE(
                (
                    SELECT activity.timed_start
                    FROM calendar_activity activity
                    WHERE activity.id = node.activity_id
                        AND activity.plan_id = node.plan_id
                        AND activity.workspace_id = node.workspace_id
                        AND activity.created_by_user_id =
                            node.created_by_user_id
                ),
                plan.timed_start)
                + make_interval(secs => node.offset_seconds)
        WHEN 'OWNER_END_OFFSET' THEN
            COALESCE(
                (
                    SELECT COALESCE(activity.timed_end, activity.timed_start)
                    FROM calendar_activity activity
                    WHERE activity.id = node.activity_id
                        AND activity.plan_id = node.plan_id
                        AND activity.workspace_id = node.workspace_id
                        AND activity.created_by_user_id =
                            node.created_by_user_id
                ),
                plan.timed_end,
                plan.timed_start)
                + make_interval(secs => node.offset_seconds)
        ELSE node.resolved_time
    END
FROM calendar_plan plan
WHERE plan.id = node.plan_id
    AND plan.workspace_id = node.workspace_id
    AND plan.created_by_user_id = node.created_by_user_id
    AND node.expression_kind IN ('OWNER_START_OFFSET', 'OWNER_END_OFFSET');

UPDATE calendar_time_node node
SET resolved_time = base.resolved_time + make_interval(secs => node.offset_seconds)
FROM calendar_time_node base
WHERE node.expression_kind = 'NODE_OFFSET'
    AND base.plan_id = node.plan_id
    AND base.workspace_id = node.workspace_id
    AND base.created_by_user_id = node.created_by_user_id
    AND base.node_key = node.base_node_key;

ALTER TABLE calendar_time_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node FORCE ROW LEVEL SECURITY;

CREATE TABLE calendar_reminder_rule (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    node_id UUID NOT NULL,
    owner_kind VARCHAR(30) NOT NULL,
    rule_kind VARCHAR(20) NOT NULL,
    offset_seconds BIGINT,
    absolute_fire_at TIMESTAMPTZ,
    delivery_mode VARCHAR(30) NOT NULL,
    ack_interval_seconds BIGINT,
    max_alerts INTEGER,
    preferred_channel VARCHAR(30),
    status VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_reminder_rule_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_reminder_rule_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_reminder_rule_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_reminder_rule_owner
        CHECK (owner_kind IN ('PERSONAL', 'SHARED_TEMPLATE')),
    CONSTRAINT chk_calendar_reminder_rule_kind
        CHECK (
            (
                rule_kind = 'RELATIVE'
                AND offset_seconds IS NOT NULL
                AND offset_seconds <= 0
                AND absolute_fire_at IS NULL
            )
            OR (
                rule_kind = 'ABSOLUTE'
                AND offset_seconds IS NULL
                AND absolute_fire_at IS NOT NULL
            )
        ),
    CONSTRAINT chk_calendar_reminder_delivery
        CHECK (
            (
                delivery_mode = 'ONCE'
                AND ack_interval_seconds IS NULL
                AND max_alerts IS NULL
            )
            OR (
                delivery_mode = 'ACK_REQUIRED'
                AND ack_interval_seconds > 0
                AND max_alerts BETWEEN 2 AND 20
            )
        ),
    CONSTRAINT chk_calendar_reminder_channel
        CHECK (
            preferred_channel IS NULL
            OR preferred_channel IN ('LOG', 'WINDOWS_TOAST', 'APNS')
        ),
    CONSTRAINT chk_calendar_reminder_status
        CHECK (
            status IN (
                'ACTIVE', 'REVIEW_REQUIRED', 'ACKNOWLEDGED', 'CANCELED')
        ),
    CONSTRAINT chk_calendar_reminder_revision CHECK (revision > 0)
);

CREATE INDEX idx_calendar_reminder_rule_node
    ON calendar_reminder_rule (
        workspace_id, created_by_user_id, node_id, owner_kind, status);

CREATE UNIQUE INDEX uq_calendar_reminder_active_semantics
    ON calendar_reminder_rule (
        node_id,
        workspace_id,
        created_by_user_id,
        owner_kind,
        rule_kind,
        COALESCE(offset_seconds, 0),
        COALESCE(absolute_fire_at, 'epoch'::timestamptz))
    WHERE status = 'ACTIVE';

CREATE TABLE calendar_reminder_occurrence (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    node_id UUID NOT NULL,
    node_revision BIGINT NOT NULL,
    rule_revision BIGINT NOT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_reminder_occurrence_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_reminder_occurrence_rule
        FOREIGN KEY (rule_id, workspace_id, created_by_user_id)
        REFERENCES calendar_reminder_rule (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_reminder_occurrence_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_reminder_occurrence_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_reminder_occurrence_identity
        UNIQUE (
            rule_id, node_revision, rule_revision, sequence_number,
            workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_reminder_occurrence_revision
        CHECK (node_revision > 0 AND rule_revision > 0),
    CONSTRAINT chk_calendar_reminder_occurrence_sequence
        CHECK (sequence_number >= 0),
    CONSTRAINT chk_calendar_reminder_occurrence_status
        CHECK (status IN ('PENDING', 'ENQUEUED', 'CANCELED'))
);

CREATE INDEX idx_calendar_reminder_occurrence_due
    ON calendar_reminder_occurrence (
        workspace_id, created_by_user_id, status, scheduled_at);

ALTER TABLE calendar_reminder_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_reminder_rule_actor ON calendar_reminder_rule
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );

ALTER TABLE calendar_reminder_occurrence ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_occurrence FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_reminder_occurrence_actor
    ON calendar_reminder_occurrence
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
