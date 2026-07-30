CREATE TABLE calendar_recurrence_series (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    lineage_root_id UUID NOT NULL,
    active_revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_recurrence_series_owner
        UNIQUE (id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_series_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_recurrence_series_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_recurrence_active_revision CHECK (active_revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_recurrence_plan_owner
    ON calendar_recurrence_series (plan_id)
    WHERE activity_id IS NULL;
CREATE UNIQUE INDEX uq_calendar_recurrence_activity_owner
    ON calendar_recurrence_series (activity_id)
    WHERE activity_id IS NOT NULL;

CREATE FUNCTION reject_nested_calendar_recurrence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.activity_id IS NULL AND EXISTS (
        SELECT 1 FROM calendar_recurrence_series existing
        WHERE existing.plan_id = NEW.plan_id
          AND existing.activity_id IS NOT NULL) THEN
        RAISE EXCEPTION 'calendar recurrence ancestry already has an activity owner';
    END IF;
    IF NEW.activity_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM calendar_recurrence_series existing
        WHERE existing.plan_id = NEW.plan_id
          AND existing.activity_id IS NULL) THEN
        RAISE EXCEPTION 'calendar recurrence ancestry already has a plan owner';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_recurrence_single_ancestry
BEFORE INSERT OR UPDATE ON calendar_recurrence_series
FOR EACH ROW EXECUTE FUNCTION reject_nested_calendar_recurrence();

CREATE TABLE calendar_recurrence_rule_revision (
    series_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    frequency VARCHAR(16) NOT NULL,
    recurrence_interval INTEGER NOT NULL,
    weekdays SMALLINT[] NOT NULL DEFAULT '{}',
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
    parent_revision INTEGER,
    split_from_timed TIMESTAMP,
    split_from_date DATE,
    state VARCHAR(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_by_actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    PRIMARY KEY (series_id, revision),
    CONSTRAINT fk_calendar_recurrence_revision_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (id, workspace_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_recurrence_revision_positive
        CHECK (revision > 0 AND recurrence_interval > 0),
    CONSTRAINT chk_calendar_recurrence_frequency
        CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT chk_calendar_recurrence_state
        CHECK (state IN ('ACTIVE', 'SUPERSEDED', 'CANCELED')),
    CONSTRAINT chk_calendar_recurrence_anchor
        CHECK ((timed_anchor IS NOT NULL AND zone_id IS NOT NULL
                AND duration_seconds >= 0 AND all_day_anchor IS NULL AND all_day_span IS NULL)
            OR (timed_anchor IS NULL AND zone_id IS NULL AND duration_seconds IS NULL
                AND all_day_anchor IS NOT NULL AND all_day_span > 0)),
    CONSTRAINT chk_calendar_recurrence_end
        CHECK ((end_kind = 'UNBOUNDED' AND occurrence_count IS NULL
                AND until_date IS NULL AND until_timed IS NULL)
            OR (end_kind = 'COUNT' AND occurrence_count > 0
                AND until_date IS NULL AND until_timed IS NULL)
            OR (end_kind = 'UNTIL_DATE' AND until_date IS NOT NULL
                AND occurrence_count IS NULL AND until_timed IS NULL)
            OR (end_kind = 'UNTIL_TIMED' AND until_timed IS NOT NULL
                AND occurrence_count IS NULL AND until_date IS NULL)),
    CONSTRAINT chk_calendar_recurrence_effective_from
        CHECK ((effective_from_timed IS NULL) <> (effective_from_date IS NULL))
);

CREATE UNIQUE INDEX uq_calendar_recurrence_single_active_revision
    ON calendar_recurrence_rule_revision (series_id)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX uq_calendar_recurrence_revision_request
    ON calendar_recurrence_rule_revision (series_id, request_hash);

CREATE TABLE calendar_recurrence_exception (
    id UUID PRIMARY KEY,
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
    request_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_by_actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurrence_exception_revision
        FOREIGN KEY (series_id, rule_revision)
        REFERENCES calendar_recurrence_rule_revision (series_id, revision),
    CONSTRAINT uq_calendar_recurrence_exception_request
        UNIQUE (series_id, request_hash),
    CONSTRAINT uq_calendar_recurrence_exception_key_kind
        UNIQUE (series_id, rule_revision, logical_timed_start, logical_all_day_start, kind),
    CONSTRAINT chk_calendar_recurrence_exception_key
        CHECK ((logical_timed_start IS NULL) <> (logical_all_day_start IS NULL)),
    CONSTRAINT chk_calendar_recurrence_exception_kind
        CHECK (kind IN ('EXCLUDED', 'ADDED', 'OVERRIDDEN'))
);

CREATE TABLE calendar_recurrence_split_audit (
    id UUID PRIMARY KEY,
    series_id UUID NOT NULL,
    previous_revision INTEGER NOT NULL,
    successor_revision INTEGER NOT NULL,
    scope VARCHAR(24) NOT NULL,
    boundary_timed TIMESTAMP,
    boundary_date DATE,
    request_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_by_actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurrence_audit_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series (id, workspace_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_recurrence_audit_request UNIQUE (series_id, request_hash),
    CONSTRAINT chk_calendar_recurrence_audit_scope
        CHECK (scope IN ('THIS_OCCURRENCE', 'THIS_AND_FUTURE', 'ENTIRE_SERIES')),
    CONSTRAINT chk_calendar_recurrence_audit_boundary
        CHECK ((boundary_timed IS NULL) <> (boundary_date IS NULL))
);

ALTER TABLE calendar_recurrence_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_series FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_rule_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_rule_revision FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_exception FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_split_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurrence_split_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_recurrence_series_effective_owner
    ON calendar_recurrence_series FOR ALL
    USING (EXISTS (
        SELECT 1 FROM calendar_plan_ownership ownership
        WHERE ownership.plan_id = calendar_recurrence_series.plan_id
          AND ownership.workspace_id = calendar_recurrence_series.workspace_id
          AND ownership.source_created_by_user_id =
                calendar_recurrence_series.source_created_by_user_id
          AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (EXISTS (
        SELECT 1 FROM calendar_plan_ownership ownership
        WHERE ownership.plan_id = calendar_recurrence_series.plan_id
          AND ownership.workspace_id = calendar_recurrence_series.workspace_id
          AND ownership.source_created_by_user_id =
                calendar_recurrence_series.source_created_by_user_id
          AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_recurrence_revision_effective_owner
    ON calendar_recurrence_rule_revision FOR ALL
    USING (EXISTS (
        SELECT 1 FROM calendar_recurrence_series series
        WHERE series.id = calendar_recurrence_rule_revision.series_id))
    WITH CHECK (EXISTS (
        SELECT 1 FROM calendar_recurrence_series series
        WHERE series.id = calendar_recurrence_rule_revision.series_id));

CREATE POLICY rls_calendar_recurrence_exception_effective_owner
    ON calendar_recurrence_exception FOR ALL
    USING (EXISTS (
        SELECT 1 FROM calendar_recurrence_series series
        WHERE series.id = calendar_recurrence_exception.series_id))
    WITH CHECK (EXISTS (
        SELECT 1 FROM calendar_recurrence_series series
        WHERE series.id = calendar_recurrence_exception.series_id));

CREATE POLICY rls_calendar_recurrence_audit_effective_owner
    ON calendar_recurrence_split_audit FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM calendar_recurrence_series series
        WHERE series.id = calendar_recurrence_split_audit.series_id));

CREATE FUNCTION reject_calendar_recurrence_audit_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'calendar recurrence audit is append-only';
END;
$$;
CREATE TRIGGER trg_calendar_recurrence_audit_append_only
BEFORE UPDATE OR DELETE ON calendar_recurrence_split_audit
FOR EACH ROW EXECUTE FUNCTION reject_calendar_recurrence_audit_mutation();
