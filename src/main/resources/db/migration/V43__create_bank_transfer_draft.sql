CREATE TABLE bank_transfer_draft (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    displayed_recipient VARCHAR(180),
    confirmed_recipient VARCHAR(180),
    purpose             VARCHAR(120) NOT NULL,
    amount_twd          INTEGER CHECK (amount_twd > 0),
    transferred_at      DATE,
    status              VARCHAR(30)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_bank_transfer_draft_actor_pending
    ON bank_transfer_draft (workspace_id, created_by_user_id, status, created_at DESC);

ALTER TABLE bank_transfer_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_transfer_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_bank_transfer_draft_actor ON bank_transfer_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
