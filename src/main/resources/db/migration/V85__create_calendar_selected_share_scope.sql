ALTER TABLE calendar_share
    DROP CONSTRAINT chk_calendar_share_shape;

DROP INDEX uq_calendar_share_active_recipient;

ALTER TABLE calendar_share_outbox
    DROP CONSTRAINT chk_calendar_share_outbox_event,
    ADD CONSTRAINT chk_calendar_share_outbox_event CHECK (
        event_type IN (
            'SHARE_CREATED', 'SHARE_REVOKED',
            'CONTENT_GRANTED', 'CONTENT_REVOKED',
            'SCOPE_REVISED'));

ALTER TABLE calendar_share
    ADD COLUMN scope_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN current_scope_snapshot_id UUID,
    ADD COLUMN semantic_fingerprint VARCHAR(64);

ALTER TABLE calendar_share NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_share DISABLE ROW LEVEL SECURITY;

UPDATE calendar_share
SET semantic_fingerprint = creation_payload_hash;

ALTER TABLE calendar_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share FORCE ROW LEVEL SECURITY;

ALTER TABLE calendar_share
    ALTER COLUMN semantic_fingerprint SET NOT NULL,
    ADD CONSTRAINT chk_calendar_share_shape CHECK (
        permission = 'VIEWER'
        AND scope_mode IN (
            'LIVE_WHOLE_PLAN',
            'SELECTED_ACTIVITIES',
            'SELECTED_NODES')),
    ADD CONSTRAINT chk_calendar_share_scope_revision
        CHECK (scope_revision >= 0),
    ADD CONSTRAINT chk_calendar_share_semantic_fingerprint
        CHECK (semantic_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_calendar_share_scope_pointer CHECK (
        (scope_mode = 'LIVE_WHOLE_PLAN'
            AND scope_revision = 0
            AND current_scope_snapshot_id IS NULL)
        OR (scope_mode IN ('SELECTED_ACTIVITIES', 'SELECTED_NODES')
            AND scope_revision > 0
            AND current_scope_snapshot_id IS NOT NULL));

CREATE UNIQUE INDEX uq_calendar_share_active_semantic
    ON calendar_share (
        workspace_id, created_by_user_id, semantic_fingerprint)
    WHERE status = 'ACTIVE';

CREATE TABLE calendar_share_scope_snapshot (
    id UUID PRIMARY KEY,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    scope_mode VARCHAR(30) NOT NULL,
    scope_revision BIGINT NOT NULL,
    grantee_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sealed_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_share_scope_snapshot_identity
        UNIQUE (
            id, share_id, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_share_scope_snapshot_mode_identity
        UNIQUE (
            id, share_id, scope_mode, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id),
    CONSTRAINT uq_calendar_share_scope_snapshot_revision
        UNIQUE (share_id, scope_revision),
    CONSTRAINT fk_calendar_share_scope_snapshot_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_calendar_share_scope_snapshot_mode CHECK (
        scope_mode IN ('SELECTED_ACTIVITIES', 'SELECTED_NODES')),
    CONSTRAINT chk_calendar_share_scope_snapshot_revision
        CHECK (scope_revision > 0)
);

ALTER TABLE calendar_share
    ADD CONSTRAINT fk_calendar_share_current_scope_snapshot
        FOREIGN KEY (
            current_scope_snapshot_id, id, scope_mode, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id)
        REFERENCES calendar_share_scope_snapshot (
            id, share_id, scope_mode, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE calendar_share_scope_item (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    scope_revision BIGINT NOT NULL,
    target_kind VARCHAR(30) NOT NULL,
    activity_id UUID,
    node_id UUID,
    target_version BIGINT NOT NULL,
    context_title VARCHAR(200),
    dependent_node_id UUID,
    dependency_minimum BOOLEAN NOT NULL DEFAULT FALSE,
    dependency_resolved_time TIMESTAMPTZ,
    dependency_revision BIGINT,
    grantee_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_share_scope_item_snapshot
        FOREIGN KEY (
            snapshot_id, share_id, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id)
        REFERENCES calendar_share_scope_snapshot (
            id, share_id, scope_revision, plan_id,
            workspace_id, created_by_user_id, grantee_user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_calendar_share_scope_item_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_share_scope_item_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_share_scope_item_dependent_node
        FOREIGN KEY (
            dependent_node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_share_scope_item_target CHECK (
        (target_kind = 'ACTIVITY'
            AND activity_id IS NOT NULL
            AND node_id IS NULL
            AND context_title IS NULL
            AND dependent_node_id IS NULL
            AND dependency_minimum = FALSE
            AND dependency_resolved_time IS NULL
            AND dependency_revision IS NULL)
        OR (target_kind = 'ACTIVITY_CONTEXT'
            AND activity_id IS NOT NULL
            AND node_id IS NULL
            AND length(btrim(context_title)) BETWEEN 1 AND 200
            AND dependent_node_id IS NULL
            AND dependency_minimum = FALSE
            AND dependency_resolved_time IS NULL
            AND dependency_revision IS NULL)
        OR (target_kind = 'NODE'
            AND activity_id IS NULL
            AND node_id IS NOT NULL
            AND context_title IS NULL
            AND dependent_node_id IS NULL
            AND dependency_minimum = FALSE
            AND dependency_resolved_time IS NULL
            AND dependency_revision IS NULL)
        OR (target_kind = 'DEPENDENCY_MINIMUM'
            AND activity_id IS NULL
            AND node_id IS NOT NULL
            AND context_title IS NULL
            AND dependent_node_id IS NOT NULL
            AND dependency_minimum = TRUE
            AND dependency_resolved_time IS NOT NULL
            AND dependency_revision IS NOT NULL)),
    CONSTRAINT chk_calendar_share_scope_item_version
        CHECK (target_version >= 0),
    CONSTRAINT chk_calendar_share_scope_item_dependency_revision
        CHECK (
            dependency_revision IS NULL
            OR dependency_revision > 0)
);

CREATE UNIQUE INDEX uq_calendar_share_scope_item_target
    ON calendar_share_scope_item (
        snapshot_id, target_kind,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(
            node_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(
            dependent_node_id,
            '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TABLE calendar_share_request_receipt (
    id UUID PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    semantic_fingerprint VARCHAR(64) NOT NULL,
    share_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    grantee_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_share_request_receipt
        UNIQUE (workspace_id, created_by_user_id, request_hash),
    CONSTRAINT fk_calendar_share_request_receipt_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT chk_calendar_share_request_receipt_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND payload_hash ~ '^[0-9a-f]{64}$'
        AND semantic_fingerprint ~ '^[0-9a-f]{64}$')
);

ALTER TABLE calendar_share_scope_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_scope_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_scope_snapshot_owner
    ON calendar_share_scope_snapshot FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_share_scope_snapshot_grantee
    ON calendar_share_scope_snapshot FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.id = calendar_share_scope_snapshot.share_id
              AND share_row.current_scope_snapshot_id =
                  calendar_share_scope_snapshot.id
              AND share_row.status = 'ACTIVE'));

ALTER TABLE calendar_share_scope_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_scope_item FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_scope_item_owner
    ON calendar_share_scope_item FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_share_scope_item_grantee
    ON calendar_share_scope_item FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(grantee_user_id)
        AND EXISTS (
            SELECT 1 FROM calendar_share share_row
            WHERE share_row.id = calendar_share_scope_item.share_id
              AND share_row.current_scope_snapshot_id =
                  calendar_share_scope_item.snapshot_id
              AND share_row.status = 'ACTIVE'));

ALTER TABLE calendar_share_request_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_share_request_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_share_request_receipt_owner
    ON calendar_share_request_receipt FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_plan_selected_recipient
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
              AND share_row.scope_mode IN (
                  'SELECTED_ACTIVITIES', 'SELECTED_NODES')
              AND share_row.status = 'ACTIVE'));

CREATE POLICY rls_calendar_activity_selected_recipient
    ON calendar_activity FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            JOIN calendar_share_scope_item item
              ON item.snapshot_id = share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE share_row.plan_id = calendar_activity.plan_id
              AND share_row.workspace_id = calendar_activity.workspace_id
              AND share_row.created_by_user_id =
                  calendar_activity.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'
              AND item.activity_id = calendar_activity.id
              AND item.target_kind = 'ACTIVITY'));

CREATE POLICY rls_calendar_time_node_selected_recipient
    ON calendar_time_node FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            JOIN calendar_share_scope_item item
              ON item.snapshot_id = share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE share_row.plan_id = calendar_time_node.plan_id
              AND share_row.workspace_id = calendar_time_node.workspace_id
              AND share_row.created_by_user_id =
                  calendar_time_node.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'
              AND item.node_id = calendar_time_node.id
              AND item.dependency_minimum = FALSE));

CREATE POLICY rls_calendar_online_link_share_recipient
    ON calendar_online_access_link FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            LEFT JOIN calendar_share_scope_item item
              ON item.snapshot_id =
                  share_row.current_scope_snapshot_id
             AND item.share_id = share_row.id
            WHERE share_row.plan_id =
                  calendar_online_access_link.plan_id
              AND share_row.workspace_id =
                  calendar_online_access_link.workspace_id
              AND share_row.created_by_user_id =
                  calendar_online_access_link.created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'
              AND (
                  (share_row.scope_mode = 'LIVE_WHOLE_PLAN')
                  OR (
                      share_row.scope_mode = 'SELECTED_ACTIVITIES'
                      AND item.target_kind = 'ACTIVITY'
                      AND item.activity_id =
                          calendar_online_access_link.activity_id
                      AND calendar_online_access_link.node_id IS NULL)
                  OR (
                      share_row.scope_mode = 'SELECTED_NODES'
                      AND item.target_kind = 'NODE'
                      AND item.node_id =
                          calendar_online_access_link.node_id
                      AND calendar_online_access_link.activity_id IS NULL))));

CREATE FUNCTION reject_calendar_share_scope_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.sealed_at IS NULL
       AND NEW.sealed_at IS NOT NULL
       AND OLD.id = NEW.id
       AND OLD.share_id = NEW.share_id
       AND OLD.plan_id = NEW.plan_id
       AND OLD.scope_mode = NEW.scope_mode
       AND OLD.scope_revision = NEW.scope_revision
       AND OLD.grantee_user_id = NEW.grantee_user_id
       AND OLD.created_at = NEW.created_at
       AND OLD.workspace_id = NEW.workspace_id
       AND OLD.created_by_user_id = NEW.created_by_user_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'calendar share scope snapshots are immutable';
END;
$$;

CREATE TRIGGER trg_calendar_share_scope_snapshot_immutable
BEFORE UPDATE OR DELETE ON calendar_share_scope_snapshot
FOR EACH ROW EXECUTE FUNCTION reject_calendar_share_scope_snapshot_mutation();

CREATE FUNCTION guard_calendar_share_scope_item_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_mode VARCHAR(30);
    snapshot_sealed_at TIMESTAMPTZ;
BEGIN
    SELECT scope_mode, sealed_at
    INTO snapshot_mode, snapshot_sealed_at
    FROM calendar_share_scope_snapshot
    WHERE id = NEW.snapshot_id
    FOR UPDATE;
    IF snapshot_mode IS NULL THEN
        RETURN NEW;
    END IF;
    IF snapshot_sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'calendar share scope snapshot is sealed';
    END IF;
    IF (snapshot_mode = 'SELECTED_ACTIVITIES'
            AND NEW.target_kind NOT IN (
                'ACTIVITY', 'NODE', 'DEPENDENCY_MINIMUM'))
       OR (snapshot_mode = 'SELECTED_NODES'
            AND NEW.target_kind NOT IN (
                'NODE', 'ACTIVITY_CONTEXT', 'DEPENDENCY_MINIMUM')) THEN
        RAISE EXCEPTION 'calendar share scope item mode mismatch';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_share_scope_item_insert_guard
BEFORE INSERT ON calendar_share_scope_item
FOR EACH ROW EXECUTE FUNCTION guard_calendar_share_scope_item_insert();

CREATE FUNCTION reject_calendar_share_scope_item_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar share scope items are immutable';
END;
$$;

CREATE TRIGGER trg_calendar_share_scope_item_immutable
BEFORE UPDATE OR DELETE ON calendar_share_scope_item
FOR EACH ROW EXECUTE FUNCTION reject_calendar_share_scope_item_mutation();

CREATE FUNCTION require_calendar_share_scope_items()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_share_scope_snapshot current_snapshot
        WHERE current_snapshot.id = NEW.id
          AND current_snapshot.sealed_at IS NOT NULL)
       OR NOT EXISTS (
        SELECT 1
        FROM calendar_share_scope_item item
        WHERE item.snapshot_id = NEW.id
          AND item.share_id = NEW.share_id
          AND item.scope_revision = NEW.scope_revision
          AND (
              (NEW.scope_mode = 'SELECTED_ACTIVITIES'
                  AND item.target_kind = 'ACTIVITY')
              OR (NEW.scope_mode = 'SELECTED_NODES'
                  AND item.target_kind = 'NODE')))
       OR EXISTS (
        SELECT 1
        FROM calendar_share_scope_item item
        WHERE item.snapshot_id = NEW.id
          AND (
              (NEW.scope_mode = 'SELECTED_ACTIVITIES'
                  AND item.target_kind NOT IN (
                      'ACTIVITY', 'NODE', 'DEPENDENCY_MINIMUM'))
              OR (NEW.scope_mode = 'SELECTED_NODES'
                  AND item.target_kind NOT IN (
                      'NODE', 'ACTIVITY_CONTEXT',
                      'DEPENDENCY_MINIMUM')))) THEN
        RAISE EXCEPTION
            'calendar selected scope requires at least one target item';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_share_scope_complete
AFTER INSERT ON calendar_share_scope_snapshot
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_calendar_share_scope_items();
