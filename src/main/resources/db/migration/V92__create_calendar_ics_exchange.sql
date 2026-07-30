ALTER TABLE calendar_recurrence_rule_revision
    ADD CONSTRAINT chk_calendar_recurrence_iso_weekdays
        CHECK (weekdays <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[]
            AND (week_start IS NULL OR week_start BETWEEN 1 AND 7)
            AND (weekday IS NULL OR weekday BETWEEN 1 AND 7)),
    ADD CONSTRAINT chk_calendar_recurrence_pattern_shape CHECK (
        (frequency = 'DAILY'
            AND cardinality(weekdays) = 0 AND week_start IS NULL
            AND month_day IS NULL AND weekday_ordinal IS NULL
            AND weekday IS NULL AND year_month IS NULL
            AND year_day IS NULL)
        OR (frequency = 'WEEKLY'
            AND cardinality(weekdays) > 0 AND week_start IS NOT NULL
            AND month_day IS NULL AND weekday_ordinal IS NULL
            AND weekday IS NULL AND year_month IS NULL
            AND year_day IS NULL)
        OR (frequency = 'MONTHLY'
            AND cardinality(weekdays) = 0 AND week_start IS NULL
            AND year_month IS NULL AND year_day IS NULL
            AND (
                (month_day IN (-1, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
                    20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                    30, 31)
                    AND weekday_ordinal IS NULL AND weekday IS NULL)
                OR (month_day IS NULL
                    AND weekday_ordinal IN (-1, 1, 2, 3, 4, 5)
                    AND weekday IS NOT NULL)))
        OR (frequency = 'YEARLY'
            AND cardinality(weekdays) = 0 AND week_start IS NULL
            AND month_day IS NULL AND weekday_ordinal IS NULL
            AND weekday IS NULL AND year_month BETWEEN 1 AND 12
            AND year_day BETWEEN 1 AND 31));

CREATE TABLE calendar_recurrence_adoption_rule_snapshot (
    recurrence_adoption_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    projection_title VARCHAR(200) NOT NULL,
    series_id UUID NOT NULL,
    rule_revision INTEGER NOT NULL,
    frequency VARCHAR(16) NOT NULL,
    recurrence_interval INTEGER NOT NULL,
    weekdays SMALLINT[] NOT NULL,
    week_start SMALLINT,
    month_day SMALLINT,
    weekday_ordinal SMALLINT,
    weekday SMALLINT,
    year_month SMALLINT,
    year_day SMALLINT,
    timed_anchor TIMESTAMP,
    duration_seconds BIGINT,
    zone_id VARCHAR(64),
    all_day_anchor DATE,
    all_day_span INTEGER,
    end_kind VARCHAR(16) NOT NULL,
    occurrence_count INTEGER,
    until_date DATE,
    until_timed TIMESTAMP,
    effective_from_timed TIMESTAMP,
    effective_from_date DATE,
    effective_until_timed TIMESTAMP,
    effective_until_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurrence_adoption_rule_snapshot
        FOREIGN KEY (
            recurrence_adoption_id, series_id,
            workspace_id, created_by_user_id)
        REFERENCES calendar_recurrence_adoption (
            id, series_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_recurrence_adoption_rule_revision
        CHECK (rule_revision > 0 AND recurrence_interval > 0)
);

CREATE TABLE calendar_recurrence_adoption_exception_snapshot (
    recurrence_adoption_id UUID NOT NULL,
    source_exception_id UUID NOT NULL,
    series_id UUID NOT NULL,
    rule_revision INTEGER NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    kind VARCHAR(16) NOT NULL,
    placement_kind VARCHAR(20),
    timed_start TIMESTAMPTZ,
    timed_end TIMESTAMPTZ,
    zone_id VARCHAR(64),
    all_day_start DATE,
    all_day_end_exclusive DATE,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    PRIMARY KEY (
        recurrence_adoption_id, source_exception_id,
        workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_adoption_exception_snapshot
        FOREIGN KEY (recurrence_adoption_id)
        REFERENCES calendar_recurrence_adoption_rule_snapshot
            (recurrence_adoption_id),
    CONSTRAINT chk_calendar_recurrence_adoption_exception_key
        CHECK ((logical_timed_start IS NULL)
            <> (logical_all_day_start IS NULL)),
    CONSTRAINT chk_calendar_recurrence_adoption_exception_kind
        CHECK (kind IN ('EXCLUDED', 'ADDED', 'OVERRIDDEN'))
);

ALTER TABLE calendar_recurrence_adoption_rule_snapshot
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_adoption_rule_snapshot
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_recurrence_adoption_rule_snapshot_actor
    ON calendar_recurrence_adoption_rule_snapshot FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
ALTER TABLE calendar_recurrence_adoption_exception_snapshot
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_adoption_exception_snapshot
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_recurrence_adoption_exception_snapshot_actor
    ON calendar_recurrence_adoption_exception_snapshot FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE TABLE calendar_ics_export_artifact (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    window_start DATE NOT NULL,
    window_end_exclusive DATE NOT NULL,
    export_profile VARCHAR(20) NOT NULL,
    loss_report TEXT NOT NULL,
    storage_key VARCHAR(80) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    artifact_status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_ics_export_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_ics_export_token UNIQUE (token_hash),
    CONSTRAINT uq_calendar_ics_export_request
        UNIQUE (workspace_id, created_by_user_id, operation_request_hash),
    CONSTRAINT chk_calendar_ics_export_profile
        CHECK (export_profile IN ('COMPACT', 'ROUTE_AWARE')),
    CONSTRAINT chk_calendar_ics_export_window
        CHECK (window_end_exclusive > window_start
            AND window_end_exclusive <= window_start + 366),
    CONSTRAINT chk_calendar_ics_export_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT chk_calendar_ics_export_hashes
        CHECK (content_hash ~ '^[0-9a-f]{64}$'
            AND token_hash ~ '^[0-9a-f]{64}$'
            AND operation_request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_ics_export_status
        CHECK (artifact_status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_calendar_ics_export_lifecycle CHECK (
        (artifact_status = 'ACTIVE'
            AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (artifact_status = 'USED'
            AND consumed_at IS NOT NULL AND revoked_at IS NULL)
        OR (artifact_status = 'REVOKED'
            AND consumed_at IS NULL AND revoked_at IS NOT NULL)
        OR (artifact_status = 'EXPIRED'
            AND consumed_at IS NULL))
);

ALTER TABLE calendar_ics_export_artifact ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_ics_export_artifact FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_ics_export_actor
    ON calendar_ics_export_artifact FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
