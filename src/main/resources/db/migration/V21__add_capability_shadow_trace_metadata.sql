-- Side-effect-free capability routing metadata. The authoritative legacy router remains
-- unchanged; these bounded fields support rollout comparison without storing message text.
ALTER TABLE intent_decision_trace
    ADD COLUMN shadow_router_version   VARCHAR(100),
    ADD COLUMN shadow_disposition      VARCHAR(40),
    ADD COLUMN shadow_fallback_reason  VARCHAR(80),
    ADD COLUMN shadow_prompt_version   VARCHAR(100),
    ADD COLUMN shadow_prompt_hash      VARCHAR(64),
    ADD COLUMN shadow_token_estimate   JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN shadow_context_plan     JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD CONSTRAINT ck_intent_trace_shadow_prompt_hash
        CHECK (shadow_prompt_hash IS NULL OR shadow_prompt_hash ~ '^[0-9a-f]{64}$');
