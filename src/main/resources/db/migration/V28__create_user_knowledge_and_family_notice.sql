-- Actor-private facts explicitly taught by a user. Facts are data, never executable prompts.
CREATE TABLE user_knowledge_fact (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID          NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID          NOT NULL REFERENCES app_user (id),
    category           VARCHAR(30)   NOT NULL,
    subject            VARCHAR(160)  NOT NULL,
    normalized_subject VARCHAR(160)  NOT NULL,
    detail             VARCHAR(1200) NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_user_knowledge_fact_actor_subject
        UNIQUE (workspace_id, created_by_user_id, category, normalized_subject),
    CONSTRAINT chk_user_knowledge_fact_category
        CHECK (category IN ('RELATIONSHIP', 'PLACE_GUIDANCE', 'INTERPRETATION_PREFERENCE'))
);

CREATE INDEX idx_user_knowledge_fact_actor_category
    ON user_knowledge_fact (workspace_id, created_by_user_id, category, updated_at DESC);

ALTER TABLE user_knowledge_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_knowledge_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_user_knowledge_fact_actor ON user_knowledge_fact
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

-- Teacher/family messages are normalized into a preview and require explicit confirmation.
CREATE TABLE family_notice_draft (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    title              VARCHAR(160) NOT NULL,
    payload            TEXT         NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_family_notice_draft_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'DISCARDED')),
    CONSTRAINT chk_family_notice_draft_payload_size
        CHECK (char_length(payload) <= 30000)
);

CREATE INDEX idx_family_notice_draft_actor_status
    ON family_notice_draft (workspace_id, created_by_user_id, status, created_at DESC);
CREATE INDEX idx_family_notice_draft_expiry
    ON family_notice_draft (expires_at)
    WHERE status = 'PENDING';

ALTER TABLE family_notice_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE family_notice_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_family_notice_draft_actor ON family_notice_draft
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_family_notice_draft_system_delete ON family_notice_draft
    FOR DELETE
    USING (app_current_scope() = 'SYSTEM');
