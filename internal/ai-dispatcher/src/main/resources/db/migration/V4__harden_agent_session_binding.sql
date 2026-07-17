ALTER TABLE agent_session
    ADD COLUMN bound_at TIMESTAMPTZ;

UPDATE agent_session
SET display_name = '開發主要對話'
WHERE session_key = 'development-main';

UPDATE agent_session
SET bound_at = COALESCE(last_verified_at, updated_at),
    last_verified_at = COALESCE(last_verified_at, updated_at)
WHERE external_session_id IS NOT NULL;

ALTER TABLE agent_session
    ADD CONSTRAINT chk_agent_session_binding_consistency
        CHECK ((status = 'UNBOUND'
                    AND external_session_id IS NULL
                    AND bound_at IS NULL
                    AND last_verified_at IS NULL)
            OR (status = 'READY'
                    AND external_session_id IS NOT NULL
                    AND bound_at IS NOT NULL
                    AND last_verified_at IS NOT NULL)
            OR (status IN ('VERIFYING', 'INVALID')
                    AND external_session_id IS NOT NULL
                    AND bound_at IS NOT NULL));

CREATE TABLE agent_session_binding_audit (
    audit_id                     UUID PRIMARY KEY,
    session_key                  VARCHAR(80) NOT NULL REFERENCES agent_session (session_key),
    binding_version              BIGINT NOT NULL,
    action                       VARCHAR(30) NOT NULL,
    previous_external_session_id VARCHAR(200),
    external_session_id          VARCHAR(200),
    actor_id                     VARCHAR(200) NOT NULL,
    reason                       VARCHAR(500),
    occurred_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_session_binding_audit_action
        CHECK (action IN ('BOUND', 'REBOUND', 'REVERIFIED', 'UNBOUND', 'INVALIDATED')),
    CONSTRAINT chk_session_binding_audit_version CHECK (binding_version >= 0)
);

CREATE INDEX idx_session_binding_audit_session_time
    ON agent_session_binding_audit (session_key, occurred_at DESC);
