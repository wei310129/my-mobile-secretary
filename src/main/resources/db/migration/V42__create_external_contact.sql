CREATE TABLE external_contact (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    canonical_key      VARCHAR(180) NOT NULL,
    display_name       VARCHAR(120) NOT NULL,
    organization_name  VARCHAR(160),
    profession         VARCHAR(120),
    phone_number       VARCHAR(100),
    email              VARCHAR(180),
    address            VARCHAR(300),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_external_contact_actor_key
        UNIQUE (workspace_id, created_by_user_id, canonical_key)
);

CREATE INDEX idx_external_contact_actor_updated
    ON external_contact (workspace_id, created_by_user_id, updated_at DESC);

ALTER TABLE external_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_external_contact_actor ON external_contact FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
