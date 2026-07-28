-- F1: raw conversation tokens are never persisted. Legacy rows receive the canonical
-- legacy-default HMAC and remain unfocused; no active focus is inferred from prior context.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
BEGIN
    IF '${conversation_scope_hmac_key_base64}' = '' THEN
        RAISE EXCEPTION 'conversation scope HMAC key is required for V60';
    END IF;
END $$;

ALTER TABLE conversation_context
    ADD COLUMN conversation_scope_digest VARCHAR(64),
    ADD COLUMN scope_key_version INTEGER;

UPDATE conversation_context
SET conversation_scope_digest = encode(hmac(convert_to(
        workspace_id::text || E'\\x1f' || created_by_user_id::text || E'\\x1flegacy\\x1f'
        || channel::text || E'\\x1flegacy-default', 'UTF8'),
        decode('${conversation_scope_hmac_key_base64}', 'base64'),
        'sha256'), 'hex'),
    scope_key_version = ${conversation_scope_current_key_version};

ALTER TABLE conversation_context
    ALTER COLUMN conversation_scope_digest SET NOT NULL,
    ALTER COLUMN scope_key_version SET NOT NULL;

ALTER TABLE conversation_context
    DROP CONSTRAINT uq_conversation_context_scope;

ALTER TABLE conversation_context
    ADD CONSTRAINT uq_conversation_context_scope
        UNIQUE (workspace_id, created_by_user_id, channel, conversation_scope_digest),
    ADD CONSTRAINT chk_conversation_context_scope_digest
        CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_conversation_context_scope_key_version
        CHECK (scope_key_version > 0);

ALTER TABLE conversation_context ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_context FORCE ROW LEVEL SECURITY;
