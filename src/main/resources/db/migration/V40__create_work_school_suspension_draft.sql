CREATE TABLE work_school_suspension_draft (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID          NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID          NOT NULL REFERENCES app_user (id),
    notice_date        DATE          NOT NULL,
    extracted_summary  VARCHAR(2000) NOT NULL,
    status             VARCHAR(30)   NOT NULL,
    verify_after       TIMESTAMPTZ   NOT NULL,
    official_summary   VARCHAR(2000),
    official_source_url VARCHAR(500),
    verified_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_suspension_draft_pending
    ON work_school_suspension_draft
        (workspace_id, created_by_user_id, status, verify_after);

ALTER TABLE work_school_suspension_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_school_suspension_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_suspension_draft_actor ON work_school_suspension_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
