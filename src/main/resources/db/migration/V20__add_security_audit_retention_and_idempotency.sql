-- Security operations are intentionally separate from conversational/debug traces:
-- they contain no raw message or token and have a longer retention window.
CREATE TABLE security_audit_event (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    workspace_id   UUID REFERENCES workspace (id),
    actor_user_id  UUID REFERENCES app_user (id),
    event_type     VARCHAR(80)  NOT NULL,
    target_type    VARCHAR(80),
    target_id      VARCHAR(160),
    outcome        VARCHAR(24)  NOT NULL,
    reason_code    VARCHAR(80),
    channel        VARCHAR(40)  NOT NULL,
    request_id     UUID,
    created_at     TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_security_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_security_audit_workspace_created
    ON security_audit_event (workspace_id, created_at DESC);
CREATE INDEX idx_security_audit_actor_created
    ON security_audit_event (actor_user_id, created_at DESC);
CREATE INDEX idx_security_audit_expiry
    ON security_audit_event (expires_at);

-- Product conversation history has a user-visible retention policy independent from
-- short-lived encrypted AI diagnostics.
ALTER TABLE line_message_log
    ADD COLUMN pinned     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE line_message_log
SET expires_at = created_at + INTERVAL '90 days';

ALTER TABLE line_message_log
    ALTER COLUMN expires_at SET NOT NULL,
    ALTER COLUMN expires_at SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '90 days');

CREATE INDEX idx_line_message_log_workspace_expiry
    ON line_message_log (workspace_id, expires_at)
    WHERE pinned = FALSE;

-- One table supports REST Idempotency-Key and signed webhook event ids. A completed
-- encrypted response can be replayed without executing the mutation a second time.
CREATE TABLE idempotency_record (
    id                    BIGSERIAL PRIMARY KEY,
    workspace_id          UUID         NOT NULL REFERENCES workspace (id),
    actor_user_id         UUID         NOT NULL REFERENCES app_user (id),
    channel               VARCHAR(40)  NOT NULL,
    idempotency_key       VARCHAR(160) NOT NULL,
    request_hash          VARCHAR(64)  NOT NULL,
    status                VARCHAR(24)  NOT NULL,
    response_action       VARCHAR(80),
    response_encrypted    BYTEA,
    response_cipher_key_id VARCHAR(120),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_scope_key
        UNIQUE (workspace_id, actor_user_id, channel, idempotency_key),
    CONSTRAINT chk_idempotency_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_idempotency_cipher
        CHECK ((response_encrypted IS NULL AND response_cipher_key_id IS NULL)
            OR (response_encrypted IS NOT NULL AND response_cipher_key_id IS NOT NULL))
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);
CREATE INDEX idx_idempotency_scope_created
    ON idempotency_record (workspace_id, actor_user_id, created_at DESC);

-- A linked platform identity remembers a safe default workspace; membership validation
-- still occurs for every request, so this is never treated as authorization by itself.
ALTER TABLE external_identity
    ADD COLUMN default_workspace_id UUID REFERENCES workspace (id);

ALTER TABLE intent_decision_trace
    ADD CONSTRAINT fk_intent_trace_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    ADD CONSTRAINT fk_intent_trace_actor FOREIGN KEY (actor_id) REFERENCES app_user (id);
