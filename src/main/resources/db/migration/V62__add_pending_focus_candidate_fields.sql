ALTER TABLE pending_focus_transition
    ADD COLUMN scope_key_version INTEGER,
    ADD COLUMN from_focus_id UUID,
    ADD COLUMN transition_type VARCHAR(30),
    ADD COLUMN root_domain VARCHAR(60),
    ADD COLUMN candidate_workflow_id UUID,
    ADD COLUMN candidate_safe_label VARCHAR(200),
    ADD COLUMN inbound_idempotency_hmac VARCHAR(64);

-- Existing pending rows have no trusted typed candidate. Leave them incomplete so acceptance fails
-- closed; do not derive an active focus or candidate from conversational history.
ALTER TABLE pending_focus_transition
    ADD CONSTRAINT chk_pending_focus_transition_hmac
        CHECK (inbound_idempotency_hmac IS NULL OR inbound_idempotency_hmac ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_pending_focus_transition_candidate
        CHECK ((transition_type IS NULL
                AND root_domain IS NULL
                AND candidate_workflow_id IS NULL
                AND candidate_safe_label IS NULL)
            OR (transition_type IN ('ENTER', 'SWITCH')
                AND root_domain IS NOT NULL
                AND candidate_workflow_id IS NOT NULL
                AND candidate_safe_label IS NOT NULL));
