CREATE TABLE conversation_focus_head (
    id UUID PRIMARY KEY, channel VARCHAR(40) NOT NULL, conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL, revision BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL, created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_conversation_focus_head_scope UNIQUE (workspace_id, created_by_user_id, channel, conversation_scope_digest),
    CONSTRAINT chk_conversation_focus_head_revision CHECK (revision >= 0),
    CONSTRAINT chk_conversation_focus_head_digest CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$')
);
CREATE TABLE conversation_focus (
    id UUID PRIMARY KEY, channel VARCHAR(40) NOT NULL, conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL, root_kind VARCHAR(20) NOT NULL, root_domain VARCHAR(60) NOT NULL,
    routing_key VARCHAR(200), workflow_id UUID, safe_label VARCHAR(200) NOT NULL,
    activity_code VARCHAR(80), activity_label VARCHAR(200), status VARCHAR(20) NOT NULL,
    close_reason VARCHAR(30), version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL, created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_conversation_focus_anchor CHECK ((root_kind = 'RESOURCE' AND routing_key IS NOT NULL AND workflow_id IS NULL) OR (root_kind IN ('WORKFLOW','ASYNC_WORK') AND routing_key IS NULL AND workflow_id IS NOT NULL)),
    CONSTRAINT chk_conversation_focus_activity CHECK ((activity_code IS NULL) = (activity_label IS NULL)),
    CONSTRAINT chk_conversation_focus_closed CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL)),
    CONSTRAINT chk_conversation_focus_digest CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_conversation_focus_scope_identity UNIQUE (id, workspace_id, created_by_user_id, channel, conversation_scope_digest)
);
CREATE UNIQUE INDEX uq_conversation_focus_active_scope ON conversation_focus (workspace_id, created_by_user_id, channel, conversation_scope_digest) WHERE status = 'ACTIVE';
CREATE TABLE focus_transition (
    id UUID PRIMARY KEY, channel VARCHAR(40) NOT NULL, conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    before_revision BIGINT NOT NULL, after_revision BIGINT NOT NULL, type VARCHAR(30) NOT NULL,
    from_focus_id UUID, to_focus_id UUID, inbound_idempotency_hmac VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, workspace_id UUID NOT NULL, created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_focus_transition_revision CHECK (after_revision = before_revision + 1),
    CONSTRAINT chk_focus_transition_digest CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_focus_transition_inbound_scope ON focus_transition (workspace_id, created_by_user_id, channel, conversation_scope_digest, inbound_idempotency_hmac);
CREATE TABLE pending_focus_transition (
    id UUID PRIMARY KEY, channel VARCHAR(40) NOT NULL, conversation_scope_digest VARCHAR(64) NOT NULL,
    base_focus_revision BIGINT NOT NULL, status VARCHAR(20) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL, created_by_user_id UUID NOT NULL
);
CREATE UNIQUE INDEX uq_pending_focus_transition_scope ON pending_focus_transition (workspace_id, created_by_user_id, channel, conversation_scope_digest) WHERE status = 'PENDING';
ALTER TABLE conversation_context ADD COLUMN conversation_focus_id UUID;
ALTER TABLE conversation_context
    DROP CONSTRAINT uq_conversation_context_scope,
    ADD CONSTRAINT fk_conversation_context_focus_scope
        FOREIGN KEY (conversation_focus_id, workspace_id, created_by_user_id, channel, conversation_scope_digest)
        REFERENCES conversation_focus (id, workspace_id, created_by_user_id, channel, conversation_scope_digest);
CREATE UNIQUE INDEX uq_conversation_context_unfocused_scope ON conversation_context (workspace_id, created_by_user_id, channel, conversation_scope_digest) WHERE conversation_focus_id IS NULL;
CREATE UNIQUE INDEX uq_conversation_context_focused_scope ON conversation_context (workspace_id, created_by_user_id, channel, conversation_scope_digest, conversation_focus_id) WHERE conversation_focus_id IS NOT NULL;
ALTER TABLE conversation_focus_head ENABLE ROW LEVEL SECURITY; ALTER TABLE conversation_focus_head FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_focus ENABLE ROW LEVEL SECURITY; ALTER TABLE conversation_focus FORCE ROW LEVEL SECURITY;
ALTER TABLE focus_transition ENABLE ROW LEVEL SECURITY; ALTER TABLE focus_transition FORCE ROW LEVEL SECURITY;
ALTER TABLE pending_focus_transition ENABLE ROW LEVEL SECURITY; ALTER TABLE pending_focus_transition FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conversation_focus_head_actor ON conversation_focus_head FOR ALL USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id)) WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_conversation_focus_actor ON conversation_focus FOR ALL USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id)) WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_focus_transition_actor ON focus_transition FOR ALL USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id)) WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_pending_focus_transition_actor ON pending_focus_transition FOR ALL USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id)) WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
