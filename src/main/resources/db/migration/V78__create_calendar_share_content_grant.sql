ALTER TABLE calendar_attachment_binding
    ADD CONSTRAINT uq_calendar_attachment_owned_plan_media
        UNIQUE (id, media_id, plan_id, workspace_id, created_by_user_id);

ALTER TABLE calendar_knowledge_excerpt
    ADD CONSTRAINT uq_calendar_excerpt_owned_plan
        UNIQUE (id, plan_id, workspace_id, created_by_user_id);

CREATE TABLE calendar_share (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    grantee_user_id UUID NOT NULL,
    permission VARCHAR(20) NOT NULL,
    scope_mode VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    share_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    revoke_request_hash VARCHAR(64),
    revoke_payload_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_share_owned_identity
        UNIQUE (
            id, plan_id, workspace_id, created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_share_creation_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT uq_calendar_share_revoke_request
        UNIQUE (workspace_id, created_by_user_id, revoke_request_hash),
    CONSTRAINT fk_calendar_share_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_share_grantee
        FOREIGN KEY (grantee_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_calendar_share_member
        FOREIGN KEY (workspace_id, grantee_user_id)
        REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT chk_calendar_share_shape CHECK (
        permission = 'VIEWER' AND scope_mode = 'LIVE_WHOLE_PLAN'),
    CONSTRAINT chk_calendar_share_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_calendar_share_revision CHECK (share_revision > 0),
    CONSTRAINT chk_calendar_share_not_self
        CHECK (grantee_user_id <> created_by_user_id),
    CONSTRAINT chk_calendar_share_hashes CHECK (
        creation_request_hash ~ '^[0-9a-f]{64}$'
        AND creation_payload_hash ~ '^[0-9a-f]{64}$'
        AND (
            (revoke_request_hash IS NULL AND revoke_payload_hash IS NULL)
            OR (
                revoke_request_hash ~ '^[0-9a-f]{64}$'
                AND revoke_payload_hash ~ '^[0-9a-f]{64}$'))),
    CONSTRAINT chk_calendar_share_lifecycle CHECK (
        (status = 'ACTIVE'
            AND revoked_at IS NULL
            AND revoke_request_hash IS NULL
            AND revoke_payload_hash IS NULL)
        OR (status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoke_request_hash IS NOT NULL
            AND revoke_payload_hash IS NOT NULL))
);

CREATE UNIQUE INDEX uq_calendar_share_active_recipient
    ON calendar_share (
        plan_id, grantee_user_id, workspace_id, created_by_user_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_calendar_share_recipient
    ON calendar_share (workspace_id, grantee_user_id, status, plan_id);

CREATE TABLE calendar_share_content_grant (
    id UUID PRIMARY KEY,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    content_kind VARCHAR(30) NOT NULL,
    attachment_binding_id UUID,
    knowledge_excerpt_id UUID,
    media_id BIGINT,
    grantee_user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    grant_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    revoke_request_hash VARCHAR(64),
    revoke_payload_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_content_grant_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_content_grant_creation_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT uq_calendar_content_grant_revoke_request
        UNIQUE (workspace_id, created_by_user_id, revoke_request_hash),
    CONSTRAINT fk_calendar_content_grant_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_content_grant_attachment
        FOREIGN KEY (
            attachment_binding_id, media_id, plan_id,
            workspace_id, created_by_user_id)
        REFERENCES calendar_attachment_binding (
            id, media_id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_content_grant_excerpt
        FOREIGN KEY (
            knowledge_excerpt_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_knowledge_excerpt (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_content_grant_shape CHECK (
        (content_kind = 'ATTACHMENT'
            AND attachment_binding_id IS NOT NULL
            AND media_id IS NOT NULL
            AND knowledge_excerpt_id IS NULL)
        OR (content_kind = 'KNOWLEDGE_EXCERPT'
            AND knowledge_excerpt_id IS NOT NULL
            AND attachment_binding_id IS NULL
            AND media_id IS NULL)),
    CONSTRAINT chk_calendar_content_grant_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_calendar_content_grant_revision CHECK (grant_revision > 0),
    CONSTRAINT chk_calendar_content_grant_hashes CHECK (
        creation_request_hash ~ '^[0-9a-f]{64}$'
        AND creation_payload_hash ~ '^[0-9a-f]{64}$'
        AND (
            (revoke_request_hash IS NULL AND revoke_payload_hash IS NULL)
            OR (
                revoke_request_hash ~ '^[0-9a-f]{64}$'
                AND revoke_payload_hash ~ '^[0-9a-f]{64}$'))),
    CONSTRAINT chk_calendar_content_grant_lifecycle CHECK (
        (status = 'ACTIVE'
            AND revoked_at IS NULL
            AND revoke_request_hash IS NULL
            AND revoke_payload_hash IS NULL)
        OR (status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoke_request_hash IS NOT NULL
            AND revoke_payload_hash IS NOT NULL))
);

CREATE UNIQUE INDEX uq_calendar_content_grant_active_content
    ON calendar_share_content_grant (
        share_id, content_kind,
        COALESCE(
            attachment_binding_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(
            knowledge_excerpt_id,
            '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';
CREATE INDEX idx_calendar_content_grant_recipient
    ON calendar_share_content_grant (
        workspace_id, grantee_user_id, status, share_id);

CREATE TABLE calendar_share_outbox (
    id UUID PRIMARY KEY,
    operation_request_hash VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    share_id UUID NOT NULL,
    content_grant_id UUID,
    grantee_user_id UUID NOT NULL,
    payload_text VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_share_outbox_operation
        UNIQUE (
            workspace_id, created_by_user_id,
            event_type, operation_request_hash),
    CONSTRAINT chk_calendar_share_outbox_hash
        CHECK (operation_request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_share_outbox_status
        CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT chk_calendar_share_outbox_event CHECK (
        event_type IN (
            'SHARE_CREATED', 'SHARE_REVOKED',
            'CONTENT_GRANTED', 'CONTENT_REVOKED'))
);

ALTER TABLE calendar_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_owner
    ON calendar_share FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_share_grantee
    ON calendar_share FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND status = 'ACTIVE'
        AND EXISTS (
            SELECT 1
            FROM workspace_member member
            JOIN app_user recipient ON recipient.id = member.user_id
            WHERE member.workspace_id = calendar_share.workspace_id
              AND member.user_id = calendar_share.grantee_user_id
              AND recipient.status = 'ACTIVE'));

ALTER TABLE calendar_share_content_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_content_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_content_grant_owner
    ON calendar_share_content_grant FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_content_grant_grantee
    ON calendar_share_content_grant FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND status = 'ACTIVE'
        AND EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.id = calendar_share_content_grant.share_id
              AND share.workspace_id =
                  calendar_share_content_grant.workspace_id
              AND share.grantee_user_id =
                  calendar_share_content_grant.grantee_user_id
              AND share.status = 'ACTIVE'));

ALTER TABLE calendar_share_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_outbox_owner
    ON calendar_share_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_excerpt_grantee
    ON calendar_knowledge_excerpt FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share_content_grant grant_row
            WHERE grant_row.knowledge_excerpt_id =
                    calendar_knowledge_excerpt.id
              AND grant_row.workspace_id =
                    calendar_knowledge_excerpt.workspace_id
              AND grant_row.status = 'ACTIVE'
              AND app_actor_matches(grant_row.grantee_user_id)));

CREATE POLICY rls_stored_media_calendar_grantee
    ON stored_media FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND status = 'AVAILABLE'
        AND EXISTS (
            SELECT 1
            FROM calendar_share_content_grant grant_row
            WHERE grant_row.media_id = stored_media.id
              AND grant_row.workspace_id = stored_media.workspace_id
              AND grant_row.status = 'ACTIVE'
              AND app_actor_matches(grant_row.grantee_user_id)));
