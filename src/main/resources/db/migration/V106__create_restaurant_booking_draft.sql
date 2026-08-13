CREATE TABLE restaurant_booking_draft (
    id UUID PRIMARY KEY,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    restaurant_name VARCHAR(200),
    dining_at TIMESTAMPTZ,
    party_size INTEGER,
    includes_child BOOLEAN NOT NULL DEFAULT FALSE,
    requires_accessibility BOOLEAN NOT NULL DEFAULT FALSE,
    includes_pet BOOLEAN NOT NULL DEFAULT FALSE,
    suspended_question_id UUID,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_restaurant_booking_draft_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$' AND scope_key_version > 0),
    CONSTRAINT chk_restaurant_booking_draft_name CHECK (
        restaurant_name IS NULL OR (
            length(btrim(restaurant_name)) BETWEEN 1 AND 200
            AND restaurant_name !~ '[[:cntrl:]]')),
    CONSTRAINT chk_restaurant_booking_draft_party CHECK (
        party_size IS NULL OR party_size BETWEEN 1 AND 100),
    CONSTRAINT chk_restaurant_booking_draft_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_restaurant_booking_draft_revision CHECK (revision > 0),
    CONSTRAINT chk_restaurant_booking_draft_expiry CHECK (expires_at > created_at),
    CONSTRAINT fk_restaurant_booking_draft_suspended_question FOREIGN KEY (
        suspended_question_id, workspace_id, created_by_user_id)
        REFERENCES conversation_pending_question (id, workspace_id, created_by_user_id)
);

CREATE UNIQUE INDEX uq_restaurant_booking_draft_pending_scope
    ON restaurant_booking_draft (
        workspace_id, created_by_user_id, channel, conversation_scope_digest)
    WHERE status = 'PENDING';

ALTER TABLE restaurant_booking_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_booking_draft FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_restaurant_booking_draft_actor
    ON restaurant_booking_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
