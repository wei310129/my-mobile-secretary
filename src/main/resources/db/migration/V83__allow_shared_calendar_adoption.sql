ALTER TABLE calendar_adoption
    ADD COLUMN source_created_by_user_id UUID;

-- Existing actor rows are hidden from the NOBYPASSRLS Flyway role because
-- migrations do not carry a request scope. This transaction restores both
-- ENABLE and FORCE before it can commit.
ALTER TABLE calendar_adoption NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption DISABLE ROW LEVEL SECURITY;

UPDATE calendar_adoption
SET source_created_by_user_id = created_by_user_id;

ALTER TABLE calendar_adoption
    ALTER COLUMN source_created_by_user_id SET NOT NULL;

ALTER TABLE calendar_adoption_node
    ADD COLUMN source_created_by_user_id UUID;

ALTER TABLE calendar_adoption_node NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node DISABLE ROW LEVEL SECURITY;

UPDATE calendar_adoption_node selected
SET source_created_by_user_id = adoption.source_created_by_user_id
FROM calendar_adoption adoption
WHERE adoption.id = selected.adoption_id
  AND adoption.plan_id = selected.plan_id
  AND adoption.workspace_id = selected.workspace_id
  AND adoption.created_by_user_id = selected.created_by_user_id;

ALTER TABLE calendar_adoption_node
    ALTER COLUMN source_created_by_user_id SET NOT NULL;

ALTER TABLE calendar_adoption
    DROP CONSTRAINT fk_calendar_adoption_plan;

ALTER TABLE calendar_adoption
    ADD CONSTRAINT uq_calendar_adoption_source_identity
        UNIQUE (
            id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id),
    ADD CONSTRAINT fk_calendar_adoption_source_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id);

ALTER TABLE calendar_adoption_node
    DROP CONSTRAINT fk_calendar_adoption_node_adoption,
    DROP CONSTRAINT fk_calendar_adoption_node_node;

ALTER TABLE calendar_adoption_node
    ADD CONSTRAINT fk_calendar_adoption_node_adoption_source
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id),
    ADD CONSTRAINT fk_calendar_adoption_node_source_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id);

CREATE TABLE calendar_adoption_history (
    id UUID PRIMARY KEY,
    adoption_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    adoption_revision BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_adoption_history_event
        UNIQUE (adoption_id, adoption_revision, event_type),
    CONSTRAINT fk_calendar_adoption_history_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT chk_calendar_adoption_history_event
        CHECK (event_type IN (
            'ADOPTED', 'PLAN_CANCELED', 'PLAN_ARCHIVED')),
    CONSTRAINT chk_calendar_adoption_history_revision
        CHECK (adoption_revision > 0)
);

CREATE INDEX idx_calendar_adoption_history_actor
    ON calendar_adoption_history (
        workspace_id, created_by_user_id, plan_id, occurred_at, id);

INSERT INTO calendar_adoption_history (
    id, adoption_id, plan_id, event_type, adoption_revision,
    occurred_at, workspace_id, created_by_user_id,
    source_created_by_user_id)
SELECT gen_random_uuid(), adoption.id, adoption.plan_id, 'ADOPTED', 1,
       adoption.created_at, adoption.workspace_id,
       adoption.created_by_user_id, adoption.source_created_by_user_id
FROM calendar_adoption adoption
ON CONFLICT DO NOTHING;

ALTER TABLE calendar_adoption ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node FORCE ROW LEVEL SECURITY;

DROP POLICY rls_calendar_adoption_actor ON calendar_adoption;

CREATE POLICY rls_calendar_adoption_actor_select
    ON calendar_adoption FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_adoption_actor_insert
    ON calendar_adoption FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND status = 'ACTIVE'
        AND EXISTS (
            SELECT 1
            FROM calendar_plan plan
            WHERE plan.id = calendar_adoption.plan_id
              AND plan.workspace_id = calendar_adoption.workspace_id
              AND plan.created_by_user_id =
                  calendar_adoption.source_created_by_user_id
              AND plan.status = 'ACTIVE'));

CREATE POLICY rls_calendar_adoption_actor_update
    ON calendar_adoption FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

DROP POLICY rls_calendar_adoption_node_actor ON calendar_adoption_node;

CREATE POLICY rls_calendar_adoption_node_actor_select
    ON calendar_adoption_node FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_adoption_node_actor_insert
    ON calendar_adoption_node FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_adoption adoption
            WHERE adoption.id = calendar_adoption_node.adoption_id
              AND adoption.plan_id = calendar_adoption_node.plan_id
              AND adoption.workspace_id =
                  calendar_adoption_node.workspace_id
              AND adoption.created_by_user_id =
                  calendar_adoption_node.created_by_user_id
              AND adoption.source_created_by_user_id =
                  calendar_adoption_node.source_created_by_user_id
              AND adoption.status = 'ACTIVE')
        AND EXISTS (
            SELECT 1
            FROM calendar_time_node node
            WHERE node.id = calendar_adoption_node.node_id
              AND node.plan_id = calendar_adoption_node.plan_id
              AND node.workspace_id =
                  calendar_adoption_node.workspace_id
              AND node.created_by_user_id =
                  calendar_adoption_node.source_created_by_user_id));

ALTER TABLE calendar_adoption_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_history FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_adoption_history_actor_select
    ON calendar_adoption_history FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_adoption_history_actor_insert
    ON calendar_adoption_history FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE POLICY rls_calendar_activity_whole_plan_recipient
    ON calendar_activity FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.plan_id = calendar_activity.plan_id
              AND share.workspace_id = calendar_activity.workspace_id
              AND share.created_by_user_id =
                  calendar_activity.created_by_user_id
              AND app_actor_matches(share.grantee_user_id)
              AND share.permission = 'VIEWER'
              AND share.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share.status = 'ACTIVE'));

CREATE POLICY rls_calendar_time_node_whole_plan_recipient
    ON calendar_time_node FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.plan_id = calendar_time_node.plan_id
              AND share.workspace_id = calendar_time_node.workspace_id
              AND share.created_by_user_id =
                  calendar_time_node.created_by_user_id
              AND app_actor_matches(share.grantee_user_id)
              AND share.permission = 'VIEWER'
              AND share.scope_mode = 'LIVE_WHOLE_PLAN'
              AND share.status = 'ACTIVE'));

CREATE FUNCTION reject_calendar_adoption_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar_adoption_history is append-only';
END;
$$;

CREATE TRIGGER trg_calendar_adoption_history_append_only
BEFORE UPDATE OR DELETE ON calendar_adoption_history
FOR EACH ROW EXECUTE FUNCTION reject_calendar_adoption_history_mutation();

CREATE FUNCTION preserve_calendar_adoption_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM NEW.created_by_user_id
       OR OLD.source_created_by_user_id IS DISTINCT FROM
           NEW.source_created_by_user_id THEN
        RAISE EXCEPTION 'calendar_adoption identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_adoption_identity_immutable
BEFORE UPDATE ON calendar_adoption
FOR EACH ROW EXECUTE FUNCTION preserve_calendar_adoption_identity();

CREATE FUNCTION reject_calendar_adoption_node_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar_adoption_node is an immutable snapshot';
END;
$$;

CREATE TRIGGER trg_calendar_adoption_node_immutable
BEFORE UPDATE OR DELETE ON calendar_adoption_node
FOR EACH ROW EXECUTE FUNCTION reject_calendar_adoption_node_mutation();

CREATE TABLE calendar_adoption_source_lifecycle (
    plan_id UUID NOT NULL,
    lifecycle_status VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    PRIMARY KEY (plan_id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_adoption_source_lifecycle_plan
        FOREIGN KEY (plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_adoption_source_lifecycle_status
        CHECK (lifecycle_status IN ('CANCELED', 'ARCHIVED'))
);

ALTER TABLE calendar_adoption_source_lifecycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_source_lifecycle FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_calendar_adoption_source_lifecycle_owner
    ON calendar_adoption_source_lifecycle FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id));

CREATE POLICY rls_calendar_adoption_source_lifecycle_adopter
    ON calendar_adoption_source_lifecycle FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_adoption adoption
            WHERE adoption.plan_id =
                  calendar_adoption_source_lifecycle.plan_id
              AND adoption.workspace_id =
                  calendar_adoption_source_lifecycle.workspace_id
              AND adoption.source_created_by_user_id =
                  calendar_adoption_source_lifecycle.source_created_by_user_id
              AND app_actor_matches(adoption.created_by_user_id)));

CREATE VIEW calendar_adoption_effective_state
WITH (security_invoker = true)
AS
SELECT adoption.id,
       adoption.plan_id,
       CASE
           WHEN lifecycle.plan_id IS NOT NULL THEN 'CANCELED'
           ELSE adoption.status
       END AS effective_status,
       adoption.status AS stored_status,
       adoption.revision,
       adoption.version,
       adoption.created_at,
       adoption.updated_at,
       adoption.workspace_id,
       adoption.created_by_user_id,
       adoption.source_created_by_user_id,
       lifecycle.lifecycle_status AS source_lifecycle_status,
       lifecycle.occurred_at AS source_lifecycle_at
FROM calendar_adoption adoption
LEFT JOIN calendar_adoption_source_lifecycle lifecycle
  ON lifecycle.plan_id = adoption.plan_id
 AND lifecycle.workspace_id = adoption.workspace_id
 AND lifecycle.source_created_by_user_id =
     adoption.source_created_by_user_id;

CREATE FUNCTION record_calendar_adoption_source_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status IN ('CANCELED', 'ARCHIVED') THEN
        INSERT INTO public.calendar_adoption_source_lifecycle (
            plan_id, lifecycle_status, occurred_at, workspace_id,
            source_created_by_user_id)
        VALUES (
            NEW.id, NEW.status, NEW.updated_at, NEW.workspace_id,
            NEW.created_by_user_id)
        ON CONFLICT (
            plan_id, workspace_id, source_created_by_user_id)
        DO UPDATE SET
            lifecycle_status = EXCLUDED.lifecycle_status,
            occurred_at = EXCLUDED.occurred_at;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION record_calendar_adoption_source_lifecycle()
    FROM PUBLIC;

CREATE TRIGGER trg_calendar_shared_adoption_lifecycle
AFTER UPDATE OF status ON calendar_plan
FOR EACH ROW EXECUTE FUNCTION record_calendar_adoption_source_lifecycle();
