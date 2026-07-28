ALTER TABLE stored_media
    ADD CONSTRAINT uq_stored_media_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

CREATE TABLE calendar_attachment_binding (
    id UUID PRIMARY KEY,
    media_id BIGINT NOT NULL,
    target_kind VARCHAR(20) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_id UUID,
    display_name VARCHAR(255) NOT NULL,
    display_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    binding_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_attachment_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_calendar_attachment_media
        FOREIGN KEY (media_id, workspace_id, created_by_user_id)
        REFERENCES stored_media (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_attachment_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_attachment_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_attachment_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_attachment_target CHECK (
        (target_kind = 'PLAN' AND activity_id IS NULL AND node_id IS NULL)
        OR (target_kind = 'ACTIVITY' AND activity_id IS NOT NULL AND node_id IS NULL)
        OR (target_kind = 'NODE' AND activity_id IS NULL AND node_id IS NOT NULL)
    ),
    CONSTRAINT chk_calendar_attachment_display
        CHECK (length(btrim(display_name)) BETWEEN 1 AND 255 AND display_order >= 0),
    CONSTRAINT chk_calendar_attachment_status
        CHECK (status IN ('ACTIVE', 'UNLINKED', 'MEDIA_DELETED')),
    CONSTRAINT chk_calendar_attachment_revision CHECK (binding_revision > 0),
    CONSTRAINT chk_calendar_attachment_hashes CHECK (
        creation_request_hash ~ '^[0-9a-f]{64}$'
        AND creation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_attachment_active_target
    ON calendar_attachment_binding (
        media_id, target_kind, plan_id,
        COALESCE(activity_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(node_id, '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_calendar_attachment_target
    ON calendar_attachment_binding (
        workspace_id, created_by_user_id, target_kind, plan_id, activity_id, node_id,
        display_order);

ALTER TABLE calendar_attachment_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_attachment_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_attachment_binding_actor
    ON calendar_attachment_binding FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
