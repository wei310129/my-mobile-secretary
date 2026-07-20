CREATE TABLE flexible_day_task_plan (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    task_id             BIGINT       NOT NULL REFERENCES task (id),
    target_date         DATE         NOT NULL,
    remind_at           TIMESTAMPTZ  NOT NULL,
    source_kind         VARCHAR(30)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_flexible_day_task_plan_task UNIQUE (task_id),
    CONSTRAINT chk_flexible_day_task_plan_source
        CHECK (source_kind IN ('USER_REQUEST', 'PAYMENT_NOTICE')),
    CONSTRAINT chk_flexible_day_task_plan_status
        CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELED'))
);

CREATE INDEX idx_flexible_day_task_plan_actor_date
    ON flexible_day_task_plan (workspace_id, created_by_user_id, target_date, status);

ALTER TABLE flexible_day_task_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE flexible_day_task_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_flexible_day_task_plan_actor ON flexible_day_task_plan FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

CREATE TABLE payment_notice (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    title               VARCHAR(200) NOT NULL,
    issuer              VARCHAR(180),
    amount_twd          INTEGER CHECK (amount_twd > 0),
    due_date            DATE         NOT NULL,
    reminder_lead_days  INTEGER CHECK (reminder_lead_days BETWEEN 0 AND 365),
    flexible_plan_id    BIGINT REFERENCES flexible_day_task_plan (id),
    status              VARCHAR(30)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_payment_notice_plan UNIQUE (flexible_plan_id),
    CONSTRAINT chk_payment_notice_status
        CHECK (status IN ('PENDING_REMINDER', 'SCHEDULED', 'COMPLETED', 'CANCELED'))
);

CREATE INDEX idx_payment_notice_actor_pending
    ON payment_notice (workspace_id, created_by_user_id, status, created_at DESC);

ALTER TABLE payment_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_notice FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_payment_notice_actor ON payment_notice FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
