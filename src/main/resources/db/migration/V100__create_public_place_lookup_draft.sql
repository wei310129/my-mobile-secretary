CREATE TABLE public_place_lookup_draft (
    id UUID PRIMARY KEY,
    mode VARCHAR(30) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    category VARCHAR(40),
    candidate_keys VARCHAR(4000) NOT NULL,
    selected_key VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_public_place_lookup_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$' AND scope_key_version > 0),
    CONSTRAINT chk_public_place_lookup_mode CHECK (
        mode IN ('READ_ONLY', 'CALENDAR_LOCATION')),
    CONSTRAINT chk_public_place_lookup_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_public_place_lookup_revision CHECK (revision > 0),
    CONSTRAINT chk_public_place_lookup_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_public_place_lookup_candidates CHECK (
        length(candidate_keys) > 0
        AND candidate_keys !~ '[[:space:]]'),
    CONSTRAINT chk_public_place_lookup_selection CHECK (
        selected_key IS NULL OR selected_key !~ '[[:space:]|]')
);

CREATE UNIQUE INDEX uq_public_place_lookup_pending_scope
    ON public_place_lookup_draft (
        workspace_id, created_by_user_id, channel, conversation_scope_digest)
    WHERE status = 'PENDING';

ALTER TABLE public_place_lookup_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE public_place_lookup_draft FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_public_place_lookup_actor
    ON public_place_lookup_draft FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
