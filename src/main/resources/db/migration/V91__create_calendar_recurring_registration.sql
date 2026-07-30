CREATE TABLE calendar_recurring_registration_policy (
    policy_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    recurrence_rule_revision INTEGER NOT NULL,
    registration_scope VARCHAR(30) NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurring_policy_base
        FOREIGN KEY (policy_id, plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_registration_policy
            (id, plan_id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurring_policy_series
        FOREIGN KEY (series_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_recurrence_series
            (id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_recurring_policy_revision
        FOREIGN KEY (series_id, recurrence_rule_revision)
        REFERENCES calendar_recurrence_rule_revision (series_id, revision),
    CONSTRAINT chk_calendar_recurring_policy_scope
        CHECK (registration_scope IN ('SERIES', 'EACH_OCCURRENCE')),
    CONSTRAINT chk_calendar_recurring_policy_revision
        CHECK (recurrence_rule_revision > 0),
    CONSTRAINT chk_calendar_recurring_policy_hash
        CHECK (operation_request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE calendar_recurring_capacity_bucket (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    recurrence_rule_revision INTEGER NOT NULL,
    registration_scope VARCHAR(30) NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    committed_count INTEGER NOT NULL,
    waitlisted_count INTEGER NOT NULL,
    bucket_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurring_bucket_policy
        FOREIGN KEY (policy_id)
        REFERENCES calendar_recurring_registration_policy (policy_id),
    CONSTRAINT chk_calendar_recurring_bucket_key CHECK (
        (registration_scope = 'SERIES'
            AND logical_timed_start IS NULL
            AND logical_all_day_start IS NULL)
        OR (registration_scope = 'EACH_OCCURRENCE'
            AND ((logical_timed_start IS NULL)
                <> (logical_all_day_start IS NULL)))),
    CONSTRAINT chk_calendar_recurring_bucket_counts
        CHECK (committed_count >= 0 AND waitlisted_count >= 0
            AND bucket_revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_recurring_bucket_key
    ON calendar_recurring_capacity_bucket (
        policy_id, registration_scope,
        COALESCE(logical_timed_start, '0001-01-01 00:00:00'::timestamp),
        COALESCE(logical_all_day_start, '0001-01-01'::date));

CREATE TABLE calendar_recurring_registration (
    id UUID PRIMARY KEY,
    bucket_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    series_id UUID NOT NULL,
    recurrence_rule_revision INTEGER NOT NULL,
    registration_scope VARCHAR(30) NOT NULL,
    logical_timed_start TIMESTAMP,
    logical_all_day_start DATE,
    registration_state VARCHAR(30) NOT NULL,
    registration_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_recurring_registration_bucket
        FOREIGN KEY (bucket_id)
        REFERENCES calendar_recurring_capacity_bucket (id),
    CONSTRAINT uq_calendar_recurring_registration_actor
        UNIQUE (bucket_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_recurring_registration_request
        UNIQUE (workspace_id, created_by_user_id, operation_request_hash),
    CONSTRAINT chk_calendar_recurring_registration_state
        CHECK (registration_state IN
            ('COMMITTED', 'WAITLISTED', 'WITHDRAWN_BY_USER')),
    CONSTRAINT chk_calendar_recurring_registration_revision
        CHECK (registration_revision > 0),
    CONSTRAINT chk_calendar_recurring_registration_hash
        CHECK (operation_request_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE calendar_recurring_registration_policy
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurring_registration_policy
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_recurring_policy_visible
    ON calendar_recurring_registration_policy FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_registration_policy base
            WHERE base.id = policy_id));
CREATE POLICY rls_calendar_recurring_policy_owner_insert
    ON calendar_recurring_registration_policy FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id));

ALTER TABLE calendar_recurring_capacity_bucket
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurring_capacity_bucket
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_recurring_bucket_visible
    ON calendar_recurring_capacity_bucket FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_registration_policy base
            WHERE base.id = policy_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_registration_policy base
            WHERE base.id = policy_id));

ALTER TABLE calendar_recurring_registration
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_recurring_registration
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_recurring_registration_actor
    ON calendar_recurring_registration FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
