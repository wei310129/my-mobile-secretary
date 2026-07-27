ALTER TABLE calendar_share
    DROP CONSTRAINT chk_calendar_share_shape,
    ADD CONSTRAINT chk_calendar_share_shape CHECK (
        permission IN ('VIEWER', 'EDITOR')
        AND scope_mode IN (
            'LIVE_WHOLE_PLAN',
            'SELECTED_ACTIVITIES',
            'SELECTED_NODES'));

ALTER TABLE calendar_share_outbox
    DROP CONSTRAINT chk_calendar_share_outbox_event,
    ADD COLUMN editor_mutation_id UUID,
    ADD COLUMN authoritative_mutation_id UUID,
    ADD COLUMN life_recorded_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_calendar_share_outbox_event CHECK (
        event_type IN (
            'SHARE_CREATED', 'SHARE_REVOKED',
            'CONTENT_GRANTED', 'CONTENT_REVOKED',
            'SCOPE_REVISED', 'ROLE_CHANGED',
            'EDITOR_MUTATION', 'AUTHORITATIVE_MUTATION',
            'AUTHORITATIVE_CAPABILITY_GRANTED',
            'AUTHORITATIVE_CAPABILITY_REVOKED'));

ALTER TABLE calendar_time_node
    ADD COLUMN cancellation_status VARCHAR(20)
        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN canceled_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_calendar_time_node_cancellation CHECK (
        (cancellation_status = 'ACTIVE' AND canceled_at IS NULL)
        OR (cancellation_status = 'CANCELED'
            AND canceled_at IS NOT NULL));

CREATE TABLE calendar_share_role_audit (
    id UUID PRIMARY KEY,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    grantee_user_id UUID NOT NULL,
    previous_permission VARCHAR(20) NOT NULL,
    current_permission VARCHAR(20) NOT NULL,
    previous_share_revision BIGINT NOT NULL,
    current_share_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_share_role_audit_transition
        UNIQUE (share_id, current_share_revision),
    CONSTRAINT uq_calendar_share_role_audit_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_share_role_audit_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT chk_calendar_share_role_audit_permission CHECK (
        previous_permission IN ('VIEWER', 'EDITOR')
        AND current_permission IN ('VIEWER', 'EDITOR')
        AND previous_permission <> current_permission),
    CONSTRAINT chk_calendar_share_role_audit_revision CHECK (
        previous_share_revision > 0
        AND current_share_revision = previous_share_revision + 1),
    CONSTRAINT chk_calendar_share_role_audit_hash CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE calendar_editor_mutation_audit (
    id UUID PRIMARY KEY,
    mutation_id UUID NOT NULL,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    node_id UUID,
    activity_id UUID,
    mutation_kind VARCHAR(30) NOT NULL,
    before_value TEXT NOT NULL,
    after_value TEXT NOT NULL,
    previous_target_revision BIGINT NOT NULL,
    current_target_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    editor_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_editor_mutation_id
        UNIQUE (workspace_id, mutation_id),
    CONSTRAINT uq_calendar_editor_mutation_request
        UNIQUE (workspace_id, editor_user_id, operation_request_hash),
    CONSTRAINT fk_calendar_editor_mutation_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, editor_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_editor_mutation_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_editor_mutation_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_editor_mutation_target CHECK (
        (node_id IS NOT NULL AND activity_id IS NULL)
        OR (node_id IS NULL AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_editor_mutation_kind CHECK (
        mutation_kind IN (
            'NODE_LABEL', 'ACTIVITY_TITLE', 'ACTIVITY_CATEGORY')),
    CONSTRAINT chk_calendar_editor_mutation_revision CHECK (
        previous_target_revision >= 0
        AND current_target_revision = previous_target_revision + 1)
);

CREATE TABLE calendar_authoritative_editor_capability (
    id UUID PRIMARY KEY,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    node_id UUID,
    capability_scope VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    capability_revision BIGINT NOT NULL,
    grant_request_hash VARCHAR(64) NOT NULL,
    revoke_request_hash VARCHAR(64),
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    grantee_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_authoritative_capability_identity
        UNIQUE (
            id, workspace_id, created_by_user_id,
            grantee_user_id),
    CONSTRAINT uq_calendar_authoritative_capability_share_identity
        UNIQUE (
            id, share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_authoritative_capability_owned_scope
        UNIQUE (
            id, share_id, plan_id, node_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_authoritative_capability_grant_request
        UNIQUE (
            workspace_id, created_by_user_id,
            grant_request_hash),
    CONSTRAINT uq_calendar_authoritative_capability_revoke_request
        UNIQUE (
            workspace_id, created_by_user_id,
            revoke_request_hash),
    CONSTRAINT fk_calendar_authoritative_capability_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_authoritative_capability_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_authoritative_capability_scope CHECK (
        (capability_scope = 'PLAN' AND node_id IS NULL)
        OR (capability_scope = 'NODE' AND node_id IS NOT NULL)),
    CONSTRAINT chk_calendar_authoritative_capability_status CHECK (
        status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_calendar_authoritative_capability_revision
        CHECK (capability_revision > 0),
    CONSTRAINT chk_calendar_authoritative_capability_hashes CHECK (
        grant_request_hash ~ '^[0-9a-f]{64}$'
        AND (
            revoke_request_hash IS NULL
            OR revoke_request_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT chk_calendar_authoritative_capability_lifecycle CHECK (
        (status = 'ACTIVE'
            AND revoked_at IS NULL
            AND revoke_request_hash IS NULL)
        OR (status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoke_request_hash IS NOT NULL))
);

CREATE UNIQUE INDEX uq_calendar_authoritative_capability_active_scope
    ON calendar_authoritative_editor_capability (
        share_id, capability_scope,
        COALESCE(
            node_id,
            '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';

CREATE TABLE calendar_authoritative_mutation_audit (
    id UUID PRIMARY KEY,
    mutation_id UUID NOT NULL,
    capability_id UUID NOT NULL,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    node_id UUID NOT NULL,
    mutation_kind VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    source VARCHAR(80) NOT NULL,
    before_value TEXT NOT NULL,
    after_value TEXT NOT NULL,
    previous_target_revision BIGINT NOT NULL,
    current_target_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    editor_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_authoritative_mutation_id
        UNIQUE (workspace_id, mutation_id),
    CONSTRAINT uq_calendar_authoritative_mutation_request
        UNIQUE (workspace_id, editor_user_id, operation_request_hash),
    CONSTRAINT fk_calendar_authoritative_mutation_capability
        FOREIGN KEY (
            capability_id, share_id, plan_id,
            workspace_id, created_by_user_id, editor_user_id)
        REFERENCES calendar_authoritative_editor_capability (
            id, share_id, plan_id,
            workspace_id, created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_authoritative_mutation_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_authoritative_mutation_kind CHECK (
        mutation_kind IN (
            'NODE_TIME', 'NODE_LOCATION', 'NODE_CANCELLATION')),
    CONSTRAINT chk_calendar_authoritative_mutation_reason
        CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT chk_calendar_authoritative_mutation_source
        CHECK (length(btrim(source)) BETWEEN 1 AND 80),
    CONSTRAINT chk_calendar_authoritative_mutation_revision CHECK (
        previous_target_revision > 0
        AND current_target_revision = previous_target_revision + 1)
);

ALTER TABLE calendar_share_outbox
    ADD CONSTRAINT fk_calendar_share_outbox_editor_mutation
        FOREIGN KEY (
            workspace_id, editor_mutation_id)
        REFERENCES calendar_editor_mutation_audit (
            workspace_id, mutation_id),
    ADD CONSTRAINT fk_calendar_share_outbox_authoritative_mutation
        FOREIGN KEY (
            workspace_id, authoritative_mutation_id)
        REFERENCES calendar_authoritative_mutation_audit (
            workspace_id, mutation_id),
    ADD CONSTRAINT chk_calendar_share_outbox_life_record_handoff
        CHECK (
            (
                event_type = 'EDITOR_MUTATION'
                AND editor_mutation_id IS NOT NULL
                AND authoritative_mutation_id IS NULL
                AND life_recorded_at IS NULL)
            OR (
                event_type = 'AUTHORITATIVE_MUTATION'
                AND editor_mutation_id IS NULL
                AND authoritative_mutation_id IS NOT NULL)
            OR (
                event_type NOT IN (
                    'EDITOR_MUTATION', 'AUTHORITATIVE_MUTATION')
                AND editor_mutation_id IS NULL
                AND authoritative_mutation_id IS NULL
                AND life_recorded_at IS NULL));

CREATE UNIQUE INDEX uq_calendar_share_outbox_editor_mutation
    ON calendar_share_outbox (
        workspace_id, editor_mutation_id)
    WHERE editor_mutation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_calendar_share_outbox_authoritative_mutation
    ON calendar_share_outbox (
        workspace_id, authoritative_mutation_id)
    WHERE authoritative_mutation_id IS NOT NULL;

ALTER TABLE calendar_share_role_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_role_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_role_audit_owner
    ON calendar_share_role_audit FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_share_role_audit_grantee
    ON calendar_share_role_audit FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id));

ALTER TABLE calendar_editor_mutation_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_editor_mutation_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_editor_mutation_audit_owner
    ON calendar_editor_mutation_audit FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_editor_mutation_audit_editor
    ON calendar_editor_mutation_audit FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(editor_user_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.id =
                  calendar_editor_mutation_audit.share_id
              AND share_row.plan_id =
                  calendar_editor_mutation_audit.plan_id
              AND share_row.workspace_id =
                  calendar_editor_mutation_audit.workspace_id
              AND share_row.created_by_user_id =
                  calendar_editor_mutation_audit.created_by_user_id
              AND share_row.grantee_user_id =
                  calendar_editor_mutation_audit.editor_user_id
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'));

CREATE POLICY rls_calendar_plan_editor_lock
    ON calendar_plan FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.plan_id = calendar_plan.id
              AND share_row.workspace_id = calendar_plan.workspace_id
              AND share_row.created_by_user_id =
                  calendar_plan.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'))
    WITH CHECK (app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_share_editor_lock
    ON calendar_share FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND permission = 'EDITOR'
        AND status = 'ACTIVE')
    WITH CHECK (app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_editor_mutation_audit_editor_select
    ON calendar_editor_mutation_audit FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(editor_user_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.id =
                  calendar_editor_mutation_audit.share_id
              AND share_row.plan_id =
                  calendar_editor_mutation_audit.plan_id
              AND share_row.workspace_id =
                  calendar_editor_mutation_audit.workspace_id
              AND share_row.created_by_user_id =
                  calendar_editor_mutation_audit.created_by_user_id
              AND share_row.grantee_user_id =
                  calendar_editor_mutation_audit.editor_user_id
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'));

ALTER TABLE calendar_authoritative_editor_capability
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_authoritative_editor_capability
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_authoritative_capability_owner
    ON calendar_authoritative_editor_capability FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_authoritative_capability_grantee
    ON calendar_authoritative_editor_capability FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND status = 'ACTIVE');
CREATE POLICY rls_calendar_authoritative_capability_grantee_lock
    ON calendar_authoritative_editor_capability FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND status = 'ACTIVE')
    WITH CHECK (app_actor_matches(created_by_user_id));

ALTER TABLE calendar_authoritative_mutation_audit
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_authoritative_mutation_audit
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_authoritative_mutation_audit_owner
    ON calendar_authoritative_mutation_audit FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_authoritative_mutation_audit_editor
    ON calendar_authoritative_mutation_audit FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(editor_user_id));
CREATE POLICY rls_calendar_authoritative_mutation_audit_editor_select
    ON calendar_authoritative_mutation_audit FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(editor_user_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_authoritative_editor_capability capability
            JOIN calendar_share share_row
              ON share_row.id = capability.share_id
             AND share_row.plan_id = capability.plan_id
             AND share_row.workspace_id = capability.workspace_id
             AND share_row.created_by_user_id =
                 capability.created_by_user_id
             AND share_row.grantee_user_id =
                 capability.grantee_user_id
            WHERE capability.id =
                  calendar_authoritative_mutation_audit.capability_id
              AND capability.share_id =
                  calendar_authoritative_mutation_audit.share_id
              AND capability.workspace_id =
                  calendar_authoritative_mutation_audit.workspace_id
              AND capability.created_by_user_id =
                  calendar_authoritative_mutation_audit
                      .created_by_user_id
              AND capability.grantee_user_id =
                  calendar_authoritative_mutation_audit.editor_user_id
              AND capability.status = 'ACTIVE'
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'));

CREATE POLICY rls_calendar_share_outbox_editor_insert
    ON calendar_share_outbox FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND content_grant_id IS NULL
        AND status = 'PENDING'
        AND life_recorded_at IS NULL
        AND event_type IN (
            'EDITOR_MUTATION', 'AUTHORITATIVE_MUTATION')
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.id = calendar_share_outbox.share_id
              AND share_row.workspace_id =
                  calendar_share_outbox.workspace_id
              AND share_row.created_by_user_id =
                  calendar_share_outbox.created_by_user_id
              AND share_row.grantee_user_id =
                  calendar_share_outbox.grantee_user_id
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'));
CREATE POLICY rls_calendar_share_outbox_editor_select
    ON calendar_share_outbox FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND event_type IN (
            'EDITOR_MUTATION', 'AUTHORITATIVE_MUTATION')
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.id = calendar_share_outbox.share_id
              AND share_row.workspace_id =
                  calendar_share_outbox.workspace_id
              AND share_row.created_by_user_id =
                  calendar_share_outbox.created_by_user_id
              AND share_row.grantee_user_id =
                  calendar_share_outbox.grantee_user_id
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'));

CREATE FUNCTION calendar_authoritative_reminder_access(
    target_node_id UUID,
    target_plan_id UUID,
    target_workspace_id UUID,
    target_owner_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM calendar_authoritative_mutation_audit audit
        JOIN calendar_authoritative_editor_capability capability
          ON capability.id = audit.capability_id
         AND capability.share_id = audit.share_id
         AND capability.plan_id = audit.plan_id
         AND capability.workspace_id = audit.workspace_id
         AND capability.created_by_user_id =
             audit.created_by_user_id
         AND capability.grantee_user_id = audit.editor_user_id
        JOIN calendar_share share_row
          ON share_row.id = audit.share_id
         AND share_row.plan_id = audit.plan_id
         AND share_row.workspace_id = audit.workspace_id
         AND share_row.created_by_user_id =
             audit.created_by_user_id
         AND share_row.grantee_user_id = audit.editor_user_id
        LEFT JOIN calendar_share_scope_item item
          ON item.snapshot_id =
              share_row.current_scope_snapshot_id
         AND item.share_id = share_row.id
        WHERE audit.mutation_id::text = NULLIF(current_setting(
                  'app.calendar_authoritative_mutation_id', TRUE), '')
          AND audit.node_id = target_node_id
          AND audit.plan_id = target_plan_id
          AND audit.workspace_id = target_workspace_id
          AND audit.created_by_user_id = target_owner_id
          AND app_workspace_matches(audit.workspace_id)
          AND app_actor_matches(audit.editor_user_id)
          AND audit.mutation_kind IN (
              'NODE_TIME', 'NODE_CANCELLATION')
          AND capability.status = 'ACTIVE'
          AND (
              capability.capability_scope = 'PLAN'
              OR (
                  capability.capability_scope = 'NODE'
                  AND capability.node_id = target_node_id))
          AND share_row.permission = 'EDITOR'
          AND share_row.status = 'ACTIVE'
          AND (
              share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              OR (
                  item.target_kind = 'NODE'
                  AND item.node_id = target_node_id)));
$$;

CREATE POLICY rls_calendar_reminder_rule_authoritative_select
    ON calendar_reminder_rule FOR SELECT
    USING (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id));
CREATE POLICY rls_calendar_reminder_rule_authoritative_update
    ON calendar_reminder_rule FOR UPDATE
    USING (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id))
    WITH CHECK (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id));

CREATE POLICY rls_calendar_reminder_occurrence_authoritative_select
    ON calendar_reminder_occurrence FOR SELECT
    USING (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id));
CREATE POLICY rls_calendar_reminder_occurrence_authoritative_update
    ON calendar_reminder_occurrence FOR UPDATE
    USING (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id))
    WITH CHECK (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id));
CREATE POLICY rls_calendar_reminder_occurrence_authoritative_insert
    ON calendar_reminder_occurrence FOR INSERT
    WITH CHECK (
        calendar_authoritative_reminder_access(
            node_id, plan_id, workspace_id, created_by_user_id));

DROP POLICY rls_calendar_activity_whole_plan_recipient
    ON calendar_activity;
CREATE POLICY rls_calendar_activity_whole_plan_recipient
    ON calendar_activity FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.plan_id = calendar_activity.plan_id
              AND share_row.workspace_id = calendar_activity.workspace_id
              AND share_row.created_by_user_id =
                  calendar_activity.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission IN ('VIEWER', 'EDITOR')
              AND share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share_row.status = 'ACTIVE'));

DROP POLICY rls_calendar_plan_whole_plan_viewer ON calendar_plan;
CREATE POLICY rls_calendar_plan_whole_plan_viewer
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.plan_id = calendar_plan.id
              AND share_row.workspace_id = calendar_plan.workspace_id
              AND share_row.created_by_user_id =
                  calendar_plan.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission IN ('VIEWER', 'EDITOR')
              AND share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share_row.status = 'ACTIVE'));

DROP POLICY rls_calendar_time_node_whole_plan_recipient
    ON calendar_time_node;
CREATE POLICY rls_calendar_time_node_whole_plan_recipient
    ON calendar_time_node FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.plan_id = calendar_time_node.plan_id
              AND share_row.workspace_id = calendar_time_node.workspace_id
              AND share_row.created_by_user_id =
                  calendar_time_node.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission IN ('VIEWER', 'EDITOR')
              AND share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share_row.status = 'ACTIVE'));

CREATE POLICY rls_calendar_time_node_editor_update
    ON calendar_time_node FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            LEFT JOIN calendar_share_scope_item item
              ON item.snapshot_id =
                  share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE share_row.plan_id = calendar_time_node.plan_id
              AND share_row.workspace_id = calendar_time_node.workspace_id
              AND share_row.created_by_user_id =
                  calendar_time_node.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'
              AND (
                  share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                  OR (item.target_kind = 'NODE'
                      AND item.node_id = calendar_time_node.id))))
    WITH CHECK (
        app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_activity_editor_update
    ON calendar_activity FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            LEFT JOIN calendar_share_scope_item item
              ON item.snapshot_id =
                  share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE share_row.plan_id = calendar_activity.plan_id
              AND share_row.workspace_id = calendar_activity.workspace_id
              AND share_row.created_by_user_id =
                  calendar_activity.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'
              AND (
                  share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                  OR (item.target_kind = 'ACTIVITY'
                      AND item.activity_id = calendar_activity.id))))
    WITH CHECK (
        app_workspace_matches(workspace_id));

CREATE FUNCTION guard_calendar_editor_node_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF app_actor_matches(OLD.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    IF NULLIF(current_setting(
            'app.calendar_authoritative_mutation_id', TRUE), '')
            IS NOT NULL THEN
        IF OLD.id IS DISTINCT FROM NEW.id
           OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
           OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
           OR OLD.node_key IS DISTINCT FROM NEW.node_key
           OR OLD.label IS DISTINCT FROM NEW.label
           OR OLD.expression_kind IS DISTINCT FROM NEW.expression_kind
           OR OLD.offset_seconds IS DISTINCT FROM NEW.offset_seconds
           OR OLD.base_node_key IS DISTINCT FROM NEW.base_node_key
           OR OLD.criticality IS DISTINCT FROM NEW.criticality
           OR OLD.adjustability IS DISTINCT FROM NEW.adjustability
           OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
           OR OLD.created_by_user_id IS DISTINCT FROM
               NEW.created_by_user_id
           OR NEW.revision <> OLD.revision + 1
           OR NEW.version <> OLD.version + 1
           OR NEW.updated_at < OLD.updated_at
           OR NOT (
               (
                   OLD.expression_kind = 'ABSOLUTE'
                   AND OLD.cancellation_status = 'ACTIVE'
                   AND OLD.absolute_time IS DISTINCT FROM NEW.absolute_time
                   AND NEW.absolute_time = NEW.resolved_time
                   AND OLD.location_label IS NOT DISTINCT FROM
                       NEW.location_label
                   AND OLD.latitude IS NOT DISTINCT FROM NEW.latitude
                   AND OLD.longitude IS NOT DISTINCT FROM NEW.longitude
                   AND OLD.cancellation_status =
                       NEW.cancellation_status
                   AND OLD.canceled_at IS NOT DISTINCT FROM
                       NEW.canceled_at)
               OR (
                   OLD.cancellation_status = 'ACTIVE'
                   AND
                   OLD.absolute_time IS NOT DISTINCT FROM
                       NEW.absolute_time
                   AND OLD.resolved_time IS NOT DISTINCT FROM
                       NEW.resolved_time
                   AND OLD.cancellation_status =
                       NEW.cancellation_status
                   AND OLD.canceled_at IS NOT DISTINCT FROM
                       NEW.canceled_at
                   AND (
                       OLD.location_label IS DISTINCT FROM
                           NEW.location_label
                       OR OLD.latitude IS DISTINCT FROM NEW.latitude
                       OR OLD.longitude IS DISTINCT FROM NEW.longitude))
               OR (
                   OLD.absolute_time IS NOT DISTINCT FROM
                       NEW.absolute_time
                   AND OLD.resolved_time IS NOT DISTINCT FROM
                       NEW.resolved_time
                   AND OLD.location_label IS NOT DISTINCT FROM
                       NEW.location_label
                   AND OLD.latitude IS NOT DISTINCT FROM NEW.latitude
                   AND OLD.longitude IS NOT DISTINCT FROM NEW.longitude
                   AND OLD.cancellation_status = 'ACTIVE'
                   AND NEW.cancellation_status = 'CANCELED'
                   AND OLD.canceled_at IS NULL
                   AND NEW.canceled_at IS NOT NULL)) THEN
            RAISE EXCEPTION
                'calendar authoritative node update exceeds allowed columns';
        END IF;
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_authoritative_mutation_audit audit
            JOIN calendar_authoritative_editor_capability capability
              ON capability.id = audit.capability_id
             AND capability.share_id = audit.share_id
             AND capability.plan_id = audit.plan_id
             AND capability.workspace_id = audit.workspace_id
             AND capability.created_by_user_id =
                 audit.created_by_user_id
             AND capability.grantee_user_id =
                 audit.editor_user_id
            JOIN calendar_share share_row
              ON share_row.id = audit.share_id
             AND share_row.plan_id = audit.plan_id
             AND share_row.workspace_id = audit.workspace_id
             AND share_row.created_by_user_id =
                 audit.created_by_user_id
             AND share_row.grantee_user_id =
                 audit.editor_user_id
            LEFT JOIN calendar_share_scope_item item
              ON item.snapshot_id =
                  share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE audit.mutation_id::text = current_setting(
                      'app.calendar_authoritative_mutation_id', TRUE)
              AND audit.node_id = OLD.id
              AND audit.previous_target_revision = OLD.revision
              AND audit.current_target_revision = NEW.revision
              AND app_actor_matches(audit.editor_user_id)
              AND capability.status = 'ACTIVE'
              AND (
                  capability.capability_scope = 'PLAN'
                  OR (capability.capability_scope = 'NODE'
                      AND capability.node_id = OLD.id))
              AND share_row.permission = 'EDITOR'
              AND share_row.status = 'ACTIVE'
              AND (
                  share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                  OR (item.target_kind = 'NODE'
                      AND item.node_id = OLD.id))
              AND (
                  (
                      audit.mutation_kind = 'NODE_TIME'
                      AND OLD.expression_kind = 'ABSOLUTE'
                      AND audit.before_value =
                          OLD.absolute_time::text
                      AND audit.after_value =
                          NEW.absolute_time::text)
                  OR (
                      audit.mutation_kind = 'NODE_LOCATION'
                      AND audit.before_value = concat_ws(
                          '|',
                          COALESCE(OLD.location_label, ''),
                          COALESCE(OLD.latitude::text, ''),
                          COALESCE(OLD.longitude::text, ''))
                      AND audit.after_value = concat_ws(
                          '|',
                          COALESCE(NEW.location_label, ''),
                          COALESCE(NEW.latitude::text, ''),
                          COALESCE(NEW.longitude::text, '')))
                  OR (
                      audit.mutation_kind = 'NODE_CANCELLATION'
                      AND audit.before_value =
                          OLD.cancellation_status
                      AND audit.after_value =
                          NEW.cancellation_status))) THEN
            RAISE EXCEPTION
                'calendar authoritative mutation audit mismatch';
        END IF;
        RETURN NEW;
    END IF;
    IF NULLIF(current_setting(
            'app.calendar_editor_mutation_id', TRUE), '') IS NULL THEN
        RAISE EXCEPTION 'calendar editor mutation context is required';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
       OR OLD.node_key IS DISTINCT FROM NEW.node_key
       OR OLD.expression_kind IS DISTINCT FROM NEW.expression_kind
       OR OLD.absolute_time IS DISTINCT FROM NEW.absolute_time
       OR OLD.offset_seconds IS DISTINCT FROM NEW.offset_seconds
       OR OLD.base_node_key IS DISTINCT FROM NEW.base_node_key
       OR OLD.resolved_time IS DISTINCT FROM NEW.resolved_time
       OR OLD.location_label IS DISTINCT FROM NEW.location_label
       OR OLD.latitude IS DISTINCT FROM NEW.latitude
       OR OLD.longitude IS DISTINCT FROM NEW.longitude
       OR OLD.cancellation_status IS DISTINCT FROM
           NEW.cancellation_status
       OR OLD.canceled_at IS DISTINCT FROM NEW.canceled_at
       OR OLD.criticality IS DISTINCT FROM NEW.criticality
       OR OLD.adjustability IS DISTINCT FROM NEW.adjustability
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM
           NEW.created_by_user_id
       OR NEW.revision <> OLD.revision + 1
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'calendar editor node update exceeds allowed columns';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_editor_mutation_audit audit
        JOIN calendar_share share_row
          ON share_row.id = audit.share_id
         AND share_row.plan_id = audit.plan_id
         AND share_row.workspace_id = audit.workspace_id
         AND share_row.created_by_user_id =
             audit.created_by_user_id
         AND share_row.grantee_user_id = audit.editor_user_id
        LEFT JOIN calendar_share_scope_item item
          ON item.snapshot_id =
              share_row.current_scope_snapshot_id
         AND item.share_id = share_row.id
        WHERE audit.mutation_id::text = current_setting(
                  'app.calendar_editor_mutation_id', TRUE)
          AND audit.node_id = OLD.id
          AND audit.mutation_kind = 'NODE_LABEL'
          AND audit.before_value = OLD.label
          AND audit.after_value = NEW.label
          AND audit.previous_target_revision = OLD.revision
          AND audit.current_target_revision = NEW.revision
          AND app_actor_matches(audit.editor_user_id)
          AND share_row.permission = 'EDITOR'
          AND share_row.status = 'ACTIVE'
          AND (
              share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              OR (item.target_kind = 'NODE'
                  AND item.node_id = OLD.id))) THEN
        RAISE EXCEPTION 'calendar editor mutation audit mismatch';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_editor_node_update_guard
BEFORE UPDATE ON calendar_time_node
FOR EACH ROW EXECUTE FUNCTION guard_calendar_editor_node_update();

CREATE FUNCTION guard_calendar_editor_activity_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_kind VARCHAR(30);
    old_value TEXT;
    new_value TEXT;
BEGIN
    IF app_actor_matches(OLD.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    IF NULLIF(current_setting(
            'app.calendar_editor_mutation_id', TRUE), '') IS NULL THEN
        RAISE EXCEPTION 'calendar editor mutation context is required';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.placement_kind IS DISTINCT FROM NEW.placement_kind
       OR OLD.timed_start IS DISTINCT FROM NEW.timed_start
       OR OLD.timed_end IS DISTINCT FROM NEW.timed_end
       OR OLD.zone_id IS DISTINCT FROM NEW.zone_id
       OR OLD.all_day_start IS DISTINCT FROM NEW.all_day_start
       OR OLD.all_day_end_exclusive IS DISTINCT FROM
           NEW.all_day_end_exclusive
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM
           NEW.created_by_user_id
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at
       OR (
           OLD.title IS DISTINCT FROM NEW.title
           AND OLD.category IS DISTINCT FROM NEW.category)
       OR (
           OLD.title IS NOT DISTINCT FROM NEW.title
           AND OLD.category IS NOT DISTINCT FROM NEW.category) THEN
        RAISE EXCEPTION
            'calendar editor activity update exceeds allowed columns';
    END IF;
    IF OLD.title IS DISTINCT FROM NEW.title THEN
        expected_kind := 'ACTIVITY_TITLE';
        old_value := OLD.title;
        new_value := NEW.title;
    ELSE
        expected_kind := 'ACTIVITY_CATEGORY';
        old_value := COALESCE(OLD.category, '');
        new_value := COALESCE(NEW.category, '');
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_editor_mutation_audit audit
        JOIN calendar_share share_row
          ON share_row.id = audit.share_id
         AND share_row.plan_id = audit.plan_id
         AND share_row.workspace_id = audit.workspace_id
         AND share_row.created_by_user_id =
             audit.created_by_user_id
         AND share_row.grantee_user_id = audit.editor_user_id
        LEFT JOIN calendar_share_scope_item item
          ON item.snapshot_id =
              share_row.current_scope_snapshot_id
         AND item.share_id = share_row.id
        WHERE audit.mutation_id::text = current_setting(
                  'app.calendar_editor_mutation_id', TRUE)
          AND audit.activity_id = OLD.id
          AND audit.mutation_kind = expected_kind
          AND audit.before_value = old_value
          AND audit.after_value = new_value
          AND audit.previous_target_revision = OLD.version
          AND audit.current_target_revision = NEW.version
          AND app_actor_matches(audit.editor_user_id)
          AND share_row.permission = 'EDITOR'
          AND share_row.status = 'ACTIVE'
          AND (
              share_row.scope_mode = 'LIVE_WHOLE_PLAN'
              OR (item.target_kind = 'ACTIVITY'
                  AND item.activity_id = OLD.id))) THEN
        RAISE EXCEPTION 'calendar editor mutation audit mismatch';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_editor_activity_update_guard
BEFORE UPDATE ON calendar_activity
FOR EACH ROW EXECUTE FUNCTION guard_calendar_editor_activity_update();

CREATE FUNCTION reject_calendar_editor_lock_row_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF app_actor_matches(OLD.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'calendar editor lock rows are immutable';
END;
$$;

CREATE TRIGGER trg_calendar_editor_plan_lock_guard
BEFORE UPDATE ON calendar_plan
FOR EACH ROW EXECUTE FUNCTION reject_calendar_editor_lock_row_update();

CREATE TRIGGER trg_calendar_editor_share_lock_guard
BEFORE UPDATE ON calendar_share
FOR EACH ROW EXECUTE FUNCTION reject_calendar_editor_lock_row_update();

CREATE FUNCTION guard_calendar_authoritative_capability_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'calendar authoritative capability deletion is forbidden';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.share_id IS DISTINCT FROM NEW.share_id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.node_id IS DISTINCT FROM NEW.node_id
       OR OLD.capability_scope IS DISTINCT FROM NEW.capability_scope
       OR OLD.grant_request_hash IS DISTINCT FROM
           NEW.grant_request_hash
       OR OLD.granted_at IS DISTINCT FROM NEW.granted_at
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM NEW.created_by_user_id
       OR OLD.grantee_user_id IS DISTINCT FROM NEW.grantee_user_id
       OR OLD.status <> 'ACTIVE'
       OR NEW.status <> 'REVOKED'
       OR NEW.capability_revision <> OLD.capability_revision + 1
       OR OLD.revoke_request_hash IS NOT NULL
       OR NEW.revoke_request_hash IS NULL
       OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION
            'calendar authoritative capability transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_authoritative_capability_update_guard
BEFORE UPDATE OR DELETE ON calendar_authoritative_editor_capability
FOR EACH ROW
EXECUTE FUNCTION guard_calendar_authoritative_capability_update();

CREATE FUNCTION guard_calendar_authoritative_reminder_rule()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    mutation_kind_value VARCHAR(30);
BEGIN
    IF NULLIF(current_setting(
            'app.calendar_authoritative_mutation_id', TRUE), '')
            IS NULL THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    IF app_actor_matches(OLD.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'calendar authoritative reminder rule deletion is forbidden';
    END IF;
    SELECT audit.mutation_kind
      INTO mutation_kind_value
      FROM calendar_authoritative_mutation_audit audit
     WHERE audit.mutation_id::text = NULLIF(current_setting(
               'app.calendar_authoritative_mutation_id', TRUE), '')
       AND audit.node_id = OLD.node_id
       AND audit.plan_id = OLD.plan_id
       AND audit.workspace_id = OLD.workspace_id
       AND audit.created_by_user_id = OLD.created_by_user_id
       AND app_actor_matches(audit.editor_user_id);
    IF mutation_kind_value IS NULL
       OR OLD.id IS DISTINCT FROM NEW.id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.node_id IS DISTINCT FROM NEW.node_id
       OR OLD.owner_kind IS DISTINCT FROM NEW.owner_kind
       OR OLD.rule_kind IS DISTINCT FROM NEW.rule_kind
       OR OLD.offset_seconds IS DISTINCT FROM NEW.offset_seconds
       OR OLD.absolute_fire_at IS DISTINCT FROM NEW.absolute_fire_at
       OR OLD.delivery_mode IS DISTINCT FROM NEW.delivery_mode
       OR OLD.ack_interval_seconds IS DISTINCT FROM
           NEW.ack_interval_seconds
       OR OLD.max_alerts IS DISTINCT FROM NEW.max_alerts
       OR OLD.preferred_channel IS DISTINCT FROM
           NEW.preferred_channel
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM
           NEW.created_by_user_id
       OR NEW.revision <> OLD.revision + 1
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at
       OR NOT (
           (
               mutation_kind_value = 'NODE_TIME'
               AND OLD.status = 'ACTIVE'
               AND OLD.rule_kind = 'ABSOLUTE'
               AND NEW.status = 'REVIEW_REQUIRED')
           OR (
               mutation_kind_value = 'NODE_CANCELLATION'
               AND OLD.status = 'ACTIVE'
               AND NEW.status = 'CANCELED')) THEN
        RAISE EXCEPTION
            'calendar authoritative reminder rule transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_authoritative_reminder_rule_guard
BEFORE UPDATE OR DELETE ON calendar_reminder_rule
FOR EACH ROW
EXECUTE FUNCTION guard_calendar_authoritative_reminder_rule();

CREATE FUNCTION guard_calendar_authoritative_reminder_occurrence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    audit_row calendar_authoritative_mutation_audit%ROWTYPE;
    rule_row calendar_reminder_rule%ROWTYPE;
BEGIN
    IF NULLIF(current_setting(
            'app.calendar_authoritative_mutation_id', TRUE), '')
            IS NULL THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'INSERT'
       AND app_actor_matches(OLD.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT'
       AND app_actor_matches(NEW.created_by_user_id) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'calendar authoritative reminder occurrence deletion is forbidden';
    END IF;
    SELECT audit.*
      INTO audit_row
      FROM calendar_authoritative_mutation_audit audit
     WHERE audit.mutation_id::text = NULLIF(current_setting(
               'app.calendar_authoritative_mutation_id', TRUE), '')
       AND audit.node_id = CASE
           WHEN TG_OP = 'INSERT' THEN NEW.node_id
           ELSE OLD.node_id
       END
       AND app_actor_matches(audit.editor_user_id);
    IF audit_row.id IS NULL THEN
        RAISE EXCEPTION
            'calendar authoritative reminder occurrence audit is required';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT rule_row_value.*
          INTO rule_row
          FROM calendar_reminder_rule rule_row_value
         WHERE rule_row_value.id = NEW.rule_id
           AND rule_row_value.node_id = NEW.node_id
           AND rule_row_value.plan_id = NEW.plan_id
           AND rule_row_value.workspace_id = NEW.workspace_id
           AND rule_row_value.created_by_user_id =
               NEW.created_by_user_id;
        IF audit_row.mutation_kind <> 'NODE_TIME'
           OR rule_row.id IS NULL
           OR rule_row.status <> 'ACTIVE'
           OR rule_row.owner_kind <> 'PERSONAL'
           OR rule_row.rule_kind <> 'RELATIVE'
           OR NEW.node_revision <>
               audit_row.current_target_revision
           OR NEW.rule_revision <> rule_row.revision
           OR NEW.sequence_number <> 0
           OR NEW.status <> 'PENDING'
           OR NEW.scheduled_at <>
               audit_row.after_value::timestamptz
               + make_interval(
                   secs => rule_row.offset_seconds::double precision)
           OR NEW.plan_id <> audit_row.plan_id
           OR NEW.node_id <> audit_row.node_id
           OR NEW.workspace_id <> audit_row.workspace_id
           OR NEW.created_by_user_id <>
               audit_row.created_by_user_id THEN
            RAISE EXCEPTION
                'calendar authoritative reminder occurrence insert is invalid';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.rule_id IS DISTINCT FROM NEW.rule_id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.node_id IS DISTINCT FROM NEW.node_id
       OR OLD.node_revision IS DISTINCT FROM NEW.node_revision
       OR OLD.rule_revision IS DISTINCT FROM NEW.rule_revision
       OR OLD.sequence_number IS DISTINCT FROM NEW.sequence_number
       OR OLD.scheduled_at IS DISTINCT FROM NEW.scheduled_at
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM
           NEW.created_by_user_id
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at
       OR NEW.status <> 'CANCELED'
       OR NOT (
           (
               audit_row.mutation_kind = 'NODE_TIME'
               AND OLD.status IN ('PENDING', 'ENQUEUED'))
           OR (
               audit_row.mutation_kind = 'NODE_CANCELLATION'
               AND OLD.status IN ('PENDING', 'ENQUEUED'))) THEN
        RAISE EXCEPTION
            'calendar authoritative reminder occurrence transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_authoritative_reminder_occurrence_guard
BEFORE INSERT OR UPDATE OR DELETE ON calendar_reminder_occurrence
FOR EACH ROW
EXECUTE FUNCTION guard_calendar_authoritative_reminder_occurrence();

CREATE FUNCTION require_calendar_authoritative_capability_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_event VARCHAR(40);
BEGIN
    expected_event := CASE TG_OP
        WHEN 'INSERT' THEN 'AUTHORITATIVE_CAPABILITY_GRANTED'
        ELSE 'AUTHORITATIVE_CAPABILITY_REVOKED'
    END;
    IF TG_OP = 'INSERT'
       AND NOT EXISTS (
           SELECT 1
           FROM calendar_share share_row
           LEFT JOIN calendar_share_scope_item item
             ON item.snapshot_id =
                 share_row.current_scope_snapshot_id
            AND item.share_id = share_row.id
           WHERE share_row.id = NEW.share_id
             AND share_row.plan_id = NEW.plan_id
             AND share_row.workspace_id = NEW.workspace_id
             AND share_row.created_by_user_id =
                 NEW.created_by_user_id
             AND share_row.grantee_user_id = NEW.grantee_user_id
             AND share_row.permission = 'EDITOR'
             AND share_row.status = 'ACTIVE'
             AND (
                 (
                     NEW.capability_scope = 'PLAN'
                     AND share_row.scope_mode = 'LIVE_WHOLE_PLAN')
                 OR (
                     NEW.capability_scope = 'NODE'
                     AND (
                         share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                         OR (
                             item.target_kind = 'NODE'
                             AND item.node_id = NEW.node_id))))) THEN
        RAISE EXCEPTION
            'calendar authoritative capability exceeds active editor scope';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_share_outbox outbox
        WHERE outbox.share_id = NEW.share_id
          AND outbox.workspace_id = NEW.workspace_id
          AND outbox.created_by_user_id =
              NEW.created_by_user_id
          AND outbox.grantee_user_id = NEW.grantee_user_id
          AND outbox.event_type = expected_event
          AND outbox.payload_text LIKE
              'capabilityId=' || NEW.id::text
              || ';capabilityRevision='
              || NEW.capability_revision::text || ';%') THEN
        RAISE EXCEPTION
            'calendar authoritative capability requires durable outbox';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER
    trg_calendar_authoritative_capability_grant_requires_outbox
AFTER INSERT ON calendar_authoritative_editor_capability
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION require_calendar_authoritative_capability_outbox();

CREATE CONSTRAINT TRIGGER
    trg_calendar_authoritative_capability_revoke_requires_outbox
AFTER UPDATE ON calendar_authoritative_editor_capability
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION require_calendar_authoritative_capability_outbox();

CREATE FUNCTION reject_calendar_editor_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar editor audit is append-only';
END;
$$;

CREATE TRIGGER trg_calendar_share_role_audit_append_only
BEFORE UPDATE OR DELETE ON calendar_share_role_audit
FOR EACH ROW EXECUTE FUNCTION reject_calendar_editor_audit_mutation();
CREATE TRIGGER trg_calendar_editor_mutation_audit_append_only
BEFORE UPDATE OR DELETE ON calendar_editor_mutation_audit
FOR EACH ROW EXECUTE FUNCTION reject_calendar_editor_audit_mutation();
CREATE TRIGGER trg_calendar_authoritative_mutation_audit_append_only
BEFORE UPDATE OR DELETE ON calendar_authoritative_mutation_audit
FOR EACH ROW EXECUTE FUNCTION reject_calendar_editor_audit_mutation();

CREATE FUNCTION require_calendar_mutation_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_event VARCHAR(40);
BEGIN
    expected_event := CASE TG_TABLE_NAME
        WHEN 'calendar_editor_mutation_audit'
            THEN 'EDITOR_MUTATION'
        ELSE 'AUTHORITATIVE_MUTATION'
    END;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_share_outbox outbox
        WHERE outbox.share_id = NEW.share_id
          AND outbox.workspace_id = NEW.workspace_id
          AND outbox.created_by_user_id =
              NEW.created_by_user_id
          AND outbox.grantee_user_id =
              NEW.editor_user_id
          AND outbox.event_type = expected_event
          AND outbox.content_grant_id IS NULL
          AND outbox.status = 'PENDING'
          AND outbox.life_recorded_at IS NULL
          AND (
              (
                  expected_event = 'EDITOR_MUTATION'
                  AND outbox.editor_mutation_id =
                      NEW.mutation_id)
              OR (
                  expected_event = 'AUTHORITATIVE_MUTATION'
                  AND outbox.authoritative_mutation_id =
                      NEW.mutation_id))
          AND outbox.payload_text LIKE
              'mutationId=' || NEW.mutation_id::text || ';%') THEN
        RAISE EXCEPTION
            'calendar mutation audit requires durable outbox';
    END IF;
    IF TG_TABLE_NAME = 'calendar_editor_mutation_audit'
       AND NOT EXISTS (
           SELECT 1
           FROM calendar_share share_row
           LEFT JOIN calendar_share_scope_item item
             ON item.snapshot_id =
                 share_row.current_scope_snapshot_id
            AND item.share_id = share_row.id
           LEFT JOIN calendar_time_node node_row
             ON node_row.id = NEW.node_id
            AND node_row.plan_id = NEW.plan_id
            AND node_row.workspace_id = NEW.workspace_id
            AND node_row.created_by_user_id =
                NEW.created_by_user_id
           LEFT JOIN calendar_activity activity_row
             ON activity_row.id =
                (to_jsonb(NEW)->>'activity_id')::uuid
            AND activity_row.plan_id = NEW.plan_id
            AND activity_row.workspace_id = NEW.workspace_id
            AND activity_row.created_by_user_id =
                NEW.created_by_user_id
           WHERE share_row.id = NEW.share_id
             AND share_row.plan_id = NEW.plan_id
             AND share_row.workspace_id = NEW.workspace_id
             AND share_row.created_by_user_id =
                 NEW.created_by_user_id
             AND share_row.grantee_user_id = NEW.editor_user_id
             AND share_row.permission = 'EDITOR'
             AND share_row.status = 'ACTIVE'
             AND (
                 share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                 OR (
                     NEW.node_id IS NOT NULL
                     AND item.target_kind = 'NODE'
                     AND item.node_id = NEW.node_id)
                 OR (
                     to_jsonb(NEW)->>'activity_id' IS NOT NULL
                     AND item.target_kind = 'ACTIVITY'
                     AND item.activity_id =
                         (to_jsonb(NEW)->>'activity_id')::uuid))
             AND (
                 (
                     NEW.mutation_kind = 'NODE_LABEL'
                     AND node_row.revision =
                         NEW.current_target_revision
                     AND node_row.label = NEW.after_value)
                 OR (
                     NEW.mutation_kind = 'ACTIVITY_TITLE'
                     AND activity_row.version =
                         NEW.current_target_revision
                     AND activity_row.title = NEW.after_value)
                 OR (
                     NEW.mutation_kind = 'ACTIVITY_CATEGORY'
                     AND activity_row.version =
                         NEW.current_target_revision
                     AND COALESCE(activity_row.category, '') =
                         NEW.after_value))) THEN
        RAISE EXCEPTION
            'calendar editor audit requires exact target transition';
    END IF;
    IF TG_TABLE_NAME =
           'calendar_authoritative_mutation_audit'
       AND NOT EXISTS (
           SELECT 1
           FROM calendar_time_node node_row
           JOIN calendar_authoritative_editor_capability capability
             ON capability.id =
                (to_jsonb(NEW)->>'capability_id')::uuid
            AND capability.share_id = NEW.share_id
            AND capability.plan_id = NEW.plan_id
            AND capability.workspace_id = NEW.workspace_id
            AND capability.created_by_user_id =
                NEW.created_by_user_id
            AND capability.grantee_user_id = NEW.editor_user_id
           JOIN calendar_share share_row
             ON share_row.id = NEW.share_id
            AND share_row.plan_id = NEW.plan_id
            AND share_row.workspace_id = NEW.workspace_id
            AND share_row.created_by_user_id =
                NEW.created_by_user_id
            AND share_row.grantee_user_id = NEW.editor_user_id
           LEFT JOIN calendar_share_scope_item item
             ON item.snapshot_id =
                 share_row.current_scope_snapshot_id
            AND item.share_id = share_row.id
           WHERE node_row.id = NEW.node_id
             AND node_row.plan_id = NEW.plan_id
             AND node_row.workspace_id = NEW.workspace_id
             AND node_row.created_by_user_id =
                 NEW.created_by_user_id
             AND node_row.revision =
                 NEW.current_target_revision
             AND capability.status = 'ACTIVE'
             AND (
                 capability.capability_scope = 'PLAN'
                 OR (
                     capability.capability_scope = 'NODE'
                     AND capability.node_id = NEW.node_id))
             AND share_row.permission = 'EDITOR'
             AND share_row.status = 'ACTIVE'
             AND (
                 share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                 OR (
                     item.target_kind = 'NODE'
                     AND item.node_id = NEW.node_id))
             AND (
                 (
                     NEW.mutation_kind = 'NODE_TIME'
                     AND node_row.absolute_time::text =
                         NEW.after_value)
                 OR (
                     NEW.mutation_kind = 'NODE_LOCATION'
                     AND concat_ws(
                         '|',
                         COALESCE(node_row.location_label, ''),
                         COALESCE(node_row.latitude::text, ''),
                         COALESCE(node_row.longitude::text, '')) =
                         NEW.after_value)
                 OR (
                     NEW.mutation_kind = 'NODE_CANCELLATION'
                     AND node_row.cancellation_status =
                         NEW.after_value))) THEN
        RAISE EXCEPTION
            'calendar authoritative audit requires exact target transition';
    END IF;
    IF TG_TABLE_NAME =
           'calendar_authoritative_mutation_audit'
       AND NEW.mutation_kind = 'NODE_TIME'
       AND (
           EXISTS (
               SELECT 1
               FROM calendar_reminder_rule rule_row
               WHERE rule_row.node_id = NEW.node_id
                 AND rule_row.plan_id = NEW.plan_id
                 AND rule_row.workspace_id = NEW.workspace_id
                 AND rule_row.created_by_user_id =
                     NEW.created_by_user_id
                 AND rule_row.status = 'ACTIVE'
                 AND rule_row.rule_kind = 'ABSOLUTE')
           OR EXISTS (
               SELECT 1
               FROM calendar_reminder_rule rule_row
               WHERE rule_row.node_id = NEW.node_id
                 AND rule_row.plan_id = NEW.plan_id
                 AND rule_row.workspace_id = NEW.workspace_id
                 AND rule_row.created_by_user_id =
                     NEW.created_by_user_id
                 AND rule_row.status = 'ACTIVE'
                 AND rule_row.owner_kind = 'PERSONAL'
                 AND rule_row.rule_kind = 'RELATIVE'
                 AND (
                     SELECT count(*)
                     FROM calendar_reminder_occurrence occurrence_row
                     WHERE occurrence_row.rule_id = rule_row.id
                       AND occurrence_row.node_id = NEW.node_id
                       AND occurrence_row.plan_id = NEW.plan_id
                       AND occurrence_row.workspace_id =
                           NEW.workspace_id
                       AND occurrence_row.created_by_user_id =
                           NEW.created_by_user_id
                       AND occurrence_row.node_revision =
                           NEW.current_target_revision
                       AND occurrence_row.rule_revision =
                           rule_row.revision
                       AND occurrence_row.sequence_number = 0
                       AND occurrence_row.status = 'PENDING'
                       AND occurrence_row.scheduled_at =
                           NEW.after_value::timestamptz
                           + make_interval(
                               secs =>
                                   rule_row.offset_seconds
                                   ::double precision)) <> 1)
           OR EXISTS (
               SELECT 1
               FROM calendar_reminder_occurrence occurrence_row
               LEFT JOIN calendar_reminder_rule rule_row
                 ON rule_row.id = occurrence_row.rule_id
                AND rule_row.node_id = occurrence_row.node_id
                AND rule_row.plan_id = occurrence_row.plan_id
                AND rule_row.workspace_id =
                    occurrence_row.workspace_id
                AND rule_row.created_by_user_id =
                    occurrence_row.created_by_user_id
               WHERE occurrence_row.node_id = NEW.node_id
                 AND occurrence_row.plan_id = NEW.plan_id
                 AND occurrence_row.workspace_id = NEW.workspace_id
                 AND occurrence_row.created_by_user_id =
                     NEW.created_by_user_id
                 AND occurrence_row.status IN (
                     'PENDING', 'ENQUEUED')
                 AND NOT (
                     rule_row.status = 'ACTIVE'
                     AND rule_row.owner_kind = 'PERSONAL'
                     AND rule_row.rule_kind = 'RELATIVE'
                     AND occurrence_row.node_revision =
                         NEW.current_target_revision
                     AND occurrence_row.rule_revision =
                         rule_row.revision
                     AND occurrence_row.sequence_number = 0
                     AND occurrence_row.scheduled_at =
                         NEW.after_value::timestamptz
                         + make_interval(
                             secs =>
                                 rule_row.offset_seconds
                                 ::double precision)))) THEN
        RAISE EXCEPTION
            'calendar authoritative time requires exact reminder consequence';
    END IF;
    IF TG_TABLE_NAME =
           'calendar_authoritative_mutation_audit'
       AND NEW.mutation_kind = 'NODE_CANCELLATION'
       AND (
           EXISTS (
               SELECT 1
               FROM calendar_reminder_rule rule_row
               WHERE rule_row.node_id = NEW.node_id
                 AND rule_row.plan_id = NEW.plan_id
                 AND rule_row.workspace_id = NEW.workspace_id
                 AND rule_row.created_by_user_id =
                     NEW.created_by_user_id
                 AND rule_row.status = 'ACTIVE')
           OR EXISTS (
               SELECT 1
               FROM calendar_reminder_occurrence occurrence_row
               WHERE occurrence_row.node_id = NEW.node_id
                 AND occurrence_row.plan_id = NEW.plan_id
                 AND occurrence_row.workspace_id = NEW.workspace_id
                 AND occurrence_row.created_by_user_id =
                     NEW.created_by_user_id
                 AND occurrence_row.status IN (
                     'PENDING', 'ENQUEUED'))) THEN
        RAISE EXCEPTION
            'calendar cancellation requires terminal reminder consequence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_editor_mutation_requires_outbox
AFTER INSERT ON calendar_editor_mutation_audit
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_calendar_mutation_outbox();

CREATE CONSTRAINT TRIGGER
    trg_calendar_authoritative_mutation_requires_outbox
AFTER INSERT ON calendar_authoritative_mutation_audit
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_calendar_mutation_outbox();
