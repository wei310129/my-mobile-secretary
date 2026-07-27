ALTER TABLE calendar_attachment_binding
    DROP CONSTRAINT chk_calendar_attachment_status,
    ADD CONSTRAINT chk_calendar_attachment_status
        CHECK (status IN (
            'ACTIVE', 'UNLINKED', 'MEDIA_DELETED', 'REPLACED'));

ALTER TABLE calendar_attachment_binding
    ADD CONSTRAINT uq_calendar_attachment_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

CREATE TABLE calendar_attachment_replacement (
    id UUID PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    old_binding_id UUID NOT NULL,
    new_binding_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_attachment_replacement_request
        UNIQUE (workspace_id, created_by_user_id, request_hash),
    CONSTRAINT fk_calendar_attachment_replacement_old
        FOREIGN KEY (
            old_binding_id, workspace_id, created_by_user_id)
        REFERENCES calendar_attachment_binding (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_attachment_replacement_new
        FOREIGN KEY (
            new_binding_id, workspace_id, created_by_user_id)
        REFERENCES calendar_attachment_binding (
            id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_attachment_replacement_distinct
        CHECK (old_binding_id <> new_binding_id),
    CONSTRAINT chk_calendar_attachment_replacement_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND payload_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE calendar_attachment_replacement ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_attachment_replacement FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_attachment_replacement_owner
    ON calendar_attachment_replacement FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_plan_whole_plan_viewer
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(calendar_plan.workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.plan_id = calendar_plan.id
              AND share_row.workspace_id = calendar_plan.workspace_id
              AND share_row.created_by_user_id =
                  calendar_plan.created_by_user_id
              AND share_row.grantee_user_id = app_current_actor_id()
              AND share_row.permission = 'VIEWER'
              AND share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share_row.status = 'ACTIVE'));

CREATE FUNCTION reject_calendar_attachment_replacement_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'calendar_attachment_replacement is append-only';
END;
$$;

CREATE TRIGGER trg_calendar_attachment_replacement_append_only
BEFORE UPDATE OR DELETE ON calendar_attachment_replacement
FOR EACH ROW
EXECUTE FUNCTION reject_calendar_attachment_replacement_mutation();
