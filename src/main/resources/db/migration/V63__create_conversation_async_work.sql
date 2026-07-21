CREATE TABLE conversation_async_work (
    id UUID PRIMARY KEY,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    work_type VARCHAR(60) NOT NULL,
    workflow_id UUID NOT NULL,
    safe_label VARCHAR(200) NOT NULL,
    inbound_idempotency_hmac VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    terminal_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_conversation_async_work_digest CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_conversation_async_work_hmac CHECK (inbound_idempotency_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_conversation_async_work_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_conversation_async_work_terminal CHECK ((status = 'PENDING') = (terminal_at IS NULL))
);
CREATE UNIQUE INDEX uq_conversation_async_work_inbound_scope
    ON conversation_async_work (workspace_id, created_by_user_id, channel,
        conversation_scope_digest, inbound_idempotency_hmac);
CREATE INDEX idx_conversation_async_work_pending
    ON conversation_async_work (workspace_id, created_by_user_id, status, created_at);
ALTER TABLE conversation_async_work ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_async_work FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conversation_async_work_actor ON conversation_async_work FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
