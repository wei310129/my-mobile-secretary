CREATE TABLE event_intake_draft (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    title              VARCHAR(160) NOT NULL,
    payload            TEXT         NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_event_intake_draft_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'DISCARDED')),
    CONSTRAINT chk_event_intake_draft_payload_size
        CHECK (char_length(payload) <= 30000)
);

CREATE INDEX idx_event_intake_draft_actor_status
    ON event_intake_draft (workspace_id, created_by_user_id, status, created_at DESC);
CREATE INDEX idx_event_intake_draft_expiry
    ON event_intake_draft (expires_at) WHERE status = 'PENDING';

ALTER TABLE event_intake_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_intake_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_event_intake_draft_actor ON event_intake_draft
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_event_intake_draft_system_delete ON event_intake_draft
    FOR DELETE USING (app_current_scope() = 'SYSTEM');
