CREATE TABLE conversation_repair_draft (
    id UUID PRIMARY KEY,
    repair_kind VARCHAR(40) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    prior_action VARCHAR(40) NOT NULL,
    time_scope VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_conversation_repair_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$' AND scope_key_version > 0),
    CONSTRAINT chk_conversation_repair_kind CHECK (repair_kind IN (
        'CROSS_DOMAIN_RELATIONSHIP', 'REPEATED_QUESTION', 'FORMAT', 'WRONG_ANSWER')),
    CONSTRAINT chk_conversation_repair_prior_action CHECK (prior_action IN (
        'TASKS_LISTED', 'SCHEDULES_LISTED', 'AGENDA_LISTED', 'AGENDA_SUMMARY', 'OTHER')),
    CONSTRAINT chk_conversation_repair_time_scope CHECK (
        time_scope IN ('TODAY', 'TOMORROW', 'WEEK', 'UPCOMING')),
    CONSTRAINT chk_conversation_repair_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_conversation_repair_revision CHECK (revision > 0),
    CONSTRAINT chk_conversation_repair_expiry CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uq_conversation_repair_pending_scope
    ON conversation_repair_draft (
        workspace_id, created_by_user_id, channel, conversation_scope_digest)
    WHERE status = 'PENDING';

ALTER TABLE conversation_repair_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_repair_draft FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_conversation_repair_actor
    ON conversation_repair_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
