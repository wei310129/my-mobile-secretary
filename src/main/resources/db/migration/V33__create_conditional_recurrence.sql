CREATE TABLE conditional_recurrence_rule (
    id                   BIGSERIAL PRIMARY KEY,
    workspace_id         UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id   UUID         NOT NULL REFERENCES app_user (id),
    title                VARCHAR(200) NOT NULL,
    anchor_start_at      TIMESTAMPTZ  NOT NULL,
    duration_minutes     INTEGER      NOT NULL,
    recurrence_until     DATE,
    holiday_policy       VARCHAR(40)  NOT NULL,
    closure_policy       VARCHAR(40)  NOT NULL,
    closure_jurisdiction VARCHAR(80),
    status               VARCHAR(20)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_conditional_recurrence_duration
        CHECK (duration_minutes BETWEEN 1 AND 1440),
    CONSTRAINT chk_conditional_recurrence_holiday
        CHECK (holiday_policy IN ('NONE', 'PREVIOUS_BUSINESS_DAY')),
    CONSTRAINT chk_conditional_recurrence_closure
        CHECK (closure_policy IN ('NONE', 'NEXT_BUSINESS_DAY')),
    CONSTRAINT chk_conditional_recurrence_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED')),
    CONSTRAINT chk_conditional_recurrence_jurisdiction
        CHECK (closure_policy = 'NONE' OR NULLIF(BTRIM(closure_jurisdiction), '') IS NOT NULL)
);

CREATE TABLE conditional_recurrence_resolution (
    id                       BIGSERIAL PRIMARY KEY,
    workspace_id             UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id       UUID         NOT NULL REFERENCES app_user (id),
    rule_id                  BIGINT       NOT NULL REFERENCES conditional_recurrence_rule (id),
    base_date                DATE         NOT NULL,
    resolved_start_at        TIMESTAMPTZ,
    resolved_end_at          TIMESTAMPTZ,
    status                   VARCHAR(40)  NOT NULL,
    reason                   VARCHAR(1000) NOT NULL,
    official_source_snapshot VARCHAR(2000),
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_conditional_resolution_rule_date UNIQUE (rule_id, base_date),
    CONSTRAINT chk_conditional_resolution_status CHECK (status IN (
        'READY', 'WAITING_OFFICIAL_CONFIRMATION', 'OUTSIDE_RULE_RANGE', 'RULE_NOT_ACTIVE'
    )),
    CONSTRAINT chk_conditional_resolution_time CHECK (
        (status = 'READY' AND resolved_start_at IS NOT NULL
            AND resolved_end_at > resolved_start_at)
        OR
        (status <> 'READY' AND resolved_start_at IS NULL AND resolved_end_at IS NULL)
    )
);

CREATE INDEX idx_conditional_recurrence_actor_status
    ON conditional_recurrence_rule (workspace_id, created_by_user_id, status, anchor_start_at);
CREATE INDEX idx_conditional_resolution_actor_date
    ON conditional_recurrence_resolution (workspace_id, created_by_user_id, base_date, status);

ALTER TABLE conditional_recurrence_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE conditional_recurrence_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conditional_recurrence_rule_actor ON conditional_recurrence_rule
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE conditional_recurrence_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE conditional_recurrence_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conditional_recurrence_resolution_actor ON conditional_recurrence_resolution
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
