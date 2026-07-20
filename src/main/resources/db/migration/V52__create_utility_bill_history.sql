CREATE TABLE utility_bill_record (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    import_batch_id     UUID         NOT NULL,
    provider            VARCHAR(120),
    location_label      VARCHAR(120),
    billing_month       DATE         NOT NULL,
    usage_kwh           INTEGER CHECK (usage_kwh IS NULL OR usage_kwh >= 0),
    amount_twd          INTEGER CHECK (amount_twd IS NULL OR amount_twd >= 0),
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_utility_bill_month_first_day
        CHECK (EXTRACT(DAY FROM billing_month) = 1),
    CONSTRAINT chk_utility_bill_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'SUPERSEDED')),
    CONSTRAINT chk_utility_bill_has_measurement
        CHECK (usage_kwh IS NOT NULL OR amount_twd IS NOT NULL)
);

CREATE INDEX idx_utility_bill_pending
    ON utility_bill_record (workspace_id, created_by_user_id, status, created_at DESC);

CREATE INDEX idx_utility_bill_query
    ON utility_bill_record
       (workspace_id, created_by_user_id, location_label, billing_month DESC, status);

CREATE UNIQUE INDEX uq_utility_bill_confirmed_month
    ON utility_bill_record
       (workspace_id, created_by_user_id, COALESCE(provider, ''), location_label, billing_month)
    WHERE status = 'CONFIRMED';

ALTER TABLE utility_bill_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE utility_bill_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_utility_bill_record_actor ON utility_bill_record FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
