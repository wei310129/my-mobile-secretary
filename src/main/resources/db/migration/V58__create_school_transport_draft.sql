CREATE TABLE school_transport_draft (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID          NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID          NOT NULL REFERENCES app_user (id),
    title               VARCHAR(200)  NOT NULL,
    payload             VARCHAR(8000) NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_school_transport_draft_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'DISCARDED'))
);

CREATE INDEX idx_school_transport_draft_actor_pending
    ON school_transport_draft (workspace_id, created_by_user_id, created_at DESC)
    WHERE status = 'PENDING';

ALTER TABLE school_transport_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE school_transport_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_school_transport_draft_actor ON school_transport_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
