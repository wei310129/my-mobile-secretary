-- Minimal, privacy-aware decision trace for debugging AI routing without relying on verbose logs.
-- workspace_id and actor_id intentionally have no FK yet so this migration can precede the
-- multi-tenant identity backfill. They remain nullable only during that transition.
CREATE TABLE intent_decision_trace (
    id                       BIGSERIAL PRIMARY KEY,
    request_id               UUID         NOT NULL,
    workspace_id             UUID,
    actor_id                 UUID,
    channel                  VARCHAR(40)  NOT NULL,
    router_version           VARCHAR(100),
    prompt_version           VARCHAR(100),
    capability_version       VARCHAR(100),
    candidate_summary        JSONB        NOT NULL DEFAULT '{}'::JSONB,
    selected_capability      VARCHAR(160),
    validation_outcome       VARCHAR(40)  NOT NULL,
    validation_code          VARCHAR(80),
    execution_outcome        VARCHAR(40)  NOT NULL,
    model                    VARCHAR(160),
    input_tokens             INTEGER,
    output_tokens            INTEGER,
    stage_latencies_ms       JSONB        NOT NULL DEFAULT '{}'::JSONB,
    raw_input_encrypted      BYTEA,
    raw_output_encrypted     BYTEA,
    raw_cipher_key_id        VARCHAR(120),
    redacted_summary         TEXT,
    created_at               TIMESTAMPTZ  NOT NULL,
    raw_expires_at           TIMESTAMPTZ,
    summary_expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_intent_decision_trace_request UNIQUE (request_id),
    CONSTRAINT ck_intent_decision_trace_input_tokens
        CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_intent_decision_trace_output_tokens
        CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT ck_intent_decision_trace_raw_cipher
        CHECK ((raw_input_encrypted IS NULL AND raw_output_encrypted IS NULL AND raw_cipher_key_id IS NULL)
            OR ((raw_input_encrypted IS NOT NULL OR raw_output_encrypted IS NOT NULL)
                AND raw_cipher_key_id IS NOT NULL)),
    CONSTRAINT ck_intent_decision_trace_retention
        CHECK (summary_expires_at > created_at
            AND (raw_expires_at IS NULL
                OR (raw_expires_at > created_at AND raw_expires_at <= summary_expires_at)))
);

CREATE INDEX idx_intent_trace_created
    ON intent_decision_trace (created_at DESC);
CREATE INDEX idx_intent_trace_workspace_created
    ON intent_decision_trace (workspace_id, created_at DESC)
    WHERE workspace_id IS NOT NULL;
CREATE INDEX idx_intent_trace_actor_created
    ON intent_decision_trace (actor_id, created_at DESC)
    WHERE actor_id IS NOT NULL;
CREATE INDEX idx_intent_trace_capability_created
    ON intent_decision_trace (selected_capability, created_at DESC)
    WHERE selected_capability IS NOT NULL;
CREATE INDEX idx_intent_trace_execution_created
    ON intent_decision_trace (execution_outcome, created_at DESC);
CREATE INDEX idx_intent_trace_validation_created
    ON intent_decision_trace (validation_code, created_at DESC)
    WHERE validation_code IS NOT NULL;
CREATE INDEX idx_intent_trace_raw_expiry
    ON intent_decision_trace (raw_expires_at)
    WHERE raw_expires_at IS NOT NULL;
CREATE INDEX idx_intent_trace_summary_expiry
    ON intent_decision_trace (summary_expires_at);
