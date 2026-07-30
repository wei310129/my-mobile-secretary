CREATE TABLE calendar_recurrence_adoption (
    id UUID PRIMARY KEY,
    adoption_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    accepted_rule_revision INTEGER NOT NULL,
    adoption_scope VARCHAR(20) NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    adoption_status VARCHAR(20) NOT NULL,
    adoption_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_recurrence_adoption_identity
        UNIQUE (id, series_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_recurrence_adoption_request
        UNIQUE (workspace_id, created_by_user_id, operation_request_hash),
    CONSTRAINT fk_calendar_recurrence_adoption_base
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_adoption_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (
            id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_adoption_revision
        FOREIGN KEY (series_id, accepted_rule_revision)
        REFERENCES calendar_recurrence_rule_revision (series_id, revision),
    CONSTRAINT chk_calendar_recurrence_adoption_scope CHECK (
        (adoption_scope = 'SERIES'
            AND logical_timed_start IS NULL
            AND logical_all_day_start IS NULL)
        OR (adoption_scope = 'OCCURRENCE'
            AND ((logical_timed_start IS NULL)
                <> (logical_all_day_start IS NULL)))),
    CONSTRAINT chk_calendar_recurrence_adoption_status
        CHECK (adoption_status IN ('ACTIVE', 'CANCELED')),
    CONSTRAINT chk_calendar_recurrence_adoption_revision
        CHECK (accepted_rule_revision > 0 AND adoption_revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_recurrence_adoption_actor_scope
    ON calendar_recurrence_adoption (
        series_id, accepted_rule_revision, adoption_scope,
        COALESCE(
            logical_timed_start,
            '0001-01-01 00:00:00'::timestamp),
        COALESCE(
            logical_all_day_start,
            '0001-01-01'::date),
        workspace_id, created_by_user_id)
    WHERE adoption_status = 'ACTIVE';

CREATE TABLE calendar_recurrence_participation_exception (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    accepted_rule_revision INTEGER NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    exception_state VARCHAR(20) NOT NULL,
    exception_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_recurrence_participation_exception_request
        UNIQUE (workspace_id, created_by_user_id, operation_request_hash),
    CONSTRAINT fk_calendar_recurrence_participation_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (
            id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_participation_revision
        FOREIGN KEY (series_id, accepted_rule_revision)
        REFERENCES calendar_recurrence_rule_revision (series_id, revision),
    CONSTRAINT fk_calendar_recurrence_participation_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_recurrence_participation_key
        CHECK ((logical_timed_start IS NULL) <> (logical_all_day_start IS NULL)),
    CONSTRAINT chk_calendar_recurrence_participation_state
        CHECK (exception_state IN ('SKIPPED', 'RESTORED')),
    CONSTRAINT chk_calendar_recurrence_participation_revision
        CHECK (accepted_rule_revision > 0 AND exception_revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_recurrence_participation_actor_key
    ON calendar_recurrence_participation_exception (
        series_id, accepted_rule_revision,
        COALESCE(
            logical_timed_start,
            '0001-01-01 00:00:00'::timestamp),
        COALESCE(
            logical_all_day_start,
            '0001-01-01'::date),
        workspace_id, created_by_user_id);

CREATE TABLE calendar_recurrence_node_review (
    id UUID PRIMARY KEY,
    recurrence_adoption_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    accepted_rule_revision INTEGER NOT NULL,
    node_id UUID NOT NULL,
    node_revision BIGINT NOT NULL,
    review_state VARCHAR(20) NOT NULL,
    review_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurrence_node_review_adoption
        FOREIGN KEY (
            recurrence_adoption_id, series_id,
            workspace_id, created_by_user_id)
        REFERENCES calendar_recurrence_adoption (
            id, series_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_node_review_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_recurrence_node_review
        UNIQUE (
            recurrence_adoption_id, accepted_rule_revision,
            node_id, node_revision),
    CONSTRAINT chk_calendar_recurrence_node_review_state
        CHECK (review_state IN ('ACCEPTED', 'PENDING_REVIEW')),
    CONSTRAINT chk_calendar_recurrence_node_review_revision
        CHECK (accepted_rule_revision > 0
            AND node_revision > 0 AND review_revision > 0)
);

CREATE TABLE calendar_recurrence_reminder_materialization (
    id UUID PRIMARY KEY,
    reminder_rule_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    recurrence_rule_revision INTEGER NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    reminder_rule_revision BIGINT NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    materialization_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurrence_reminder_rule
        FOREIGN KEY (
            reminder_rule_id, workspace_id, created_by_user_id)
        REFERENCES calendar_reminder_rule (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_reminder_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (
            id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_reminder_revision
        FOREIGN KEY (series_id, recurrence_rule_revision)
        REFERENCES calendar_recurrence_rule_revision (series_id, revision),
    CONSTRAINT chk_calendar_recurrence_reminder_key
        CHECK ((logical_timed_start IS NULL) <> (logical_all_day_start IS NULL)),
    CONSTRAINT chk_calendar_recurrence_reminder_revisions
        CHECK (recurrence_rule_revision > 0 AND reminder_rule_revision > 0),
    CONSTRAINT chk_calendar_recurrence_reminder_status
        CHECK (materialization_status IN (
            'PENDING', 'ENQUEUED', 'CANCELED'))
);

CREATE UNIQUE INDEX uq_calendar_recurrence_reminder_delivery
    ON calendar_recurrence_reminder_materialization (
        reminder_rule_id, reminder_rule_revision,
        series_id, recurrence_rule_revision,
        COALESCE(
            logical_timed_start,
            '0001-01-01 00:00:00'::timestamp),
        COALESCE(
            logical_all_day_start,
            '0001-01-01'::date),
        workspace_id, created_by_user_id);

CREATE TABLE calendar_recurrence_reminder_cursor (
    reminder_rule_id UUID NOT NULL,
    series_id UUID NOT NULL,
    recurrence_rule_revision INTEGER NOT NULL,
    horizon_exclusive TIMESTAMPTZ NOT NULL,
    cursor_revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    PRIMARY KEY (
        reminder_rule_id, series_id,
        recurrence_rule_revision, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_reminder_cursor_rule
        FOREIGN KEY (
            reminder_rule_id, workspace_id, created_by_user_id)
        REFERENCES calendar_reminder_rule (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_reminder_cursor_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (
            id, workspace_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_recurrence_reminder_cursor_revision
        CHECK (recurrence_rule_revision > 0 AND cursor_revision > 0)
);

ALTER TABLE calendar_recurrence_adoption ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_adoption FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_participation_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_participation_exception FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_node_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_node_review FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_reminder_materialization ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_reminder_materialization FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_reminder_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_reminder_cursor FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_recurrence_adoption_actor
    ON calendar_recurrence_adoption FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_recurrence_participation_actor
    ON calendar_recurrence_participation_exception FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_recurrence_node_review_actor
    ON calendar_recurrence_node_review FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_recurrence_reminder_materialization_actor
    ON calendar_recurrence_reminder_materialization FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_recurrence_reminder_cursor_actor
    ON calendar_recurrence_reminder_cursor FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
