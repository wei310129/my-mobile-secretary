CREATE TABLE blood_donation_record (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    donation_date DATE NOT NULL,
    donation_location VARCHAR(180),
    next_eligible_date DATE,
    source_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_blood_donation_actor_date
        UNIQUE (workspace_id, created_by_user_id, donation_date),
    CONSTRAINT ck_blood_donation_eligibility_date
        CHECK (next_eligible_date IS NULL OR next_eligible_date >= donation_date)
);

CREATE INDEX idx_blood_donation_actor_date
    ON blood_donation_record (workspace_id, created_by_user_id, donation_date DESC);

ALTER TABLE blood_donation_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE blood_donation_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_blood_donation_actor ON blood_donation_record FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
