-- Database-level defense in depth for tenant-owned data. Account/bootstrap tables remain
-- available while an external identity is resolved; business tables fail closed without the
-- transaction-local scope set by WorkspaceRlsJpaDialect.

CREATE FUNCTION app_current_scope()
RETURNS TEXT
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.scope', TRUE), '')
$$;

CREATE FUNCTION app_current_workspace_id()
RETURNS UUID
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.workspace_id', TRUE), '')::UUID
$$;

CREATE FUNCTION app_current_actor_id()
RETURNS UUID
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.actor_id', TRUE), '')::UUID
$$;

CREATE FUNCTION app_workspace_matches(row_workspace_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT app_current_scope() IN ('REST', 'LINE', 'BACKGROUND', 'LOCAL', 'TEST')
       AND row_workspace_id = app_current_workspace_id()
$$;

CREATE FUNCTION app_actor_matches(row_actor_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT row_actor_id = app_current_actor_id()
$$;

DO $$
DECLARE
    table_name TEXT;
    tenant_tables CONSTANT TEXT[] := ARRAY[
        'task',
        'place',
        'geofence_rule',
        'reminder',
        'location_event',
        'reminder_delivery',
        'item',
        'item_place',
        'schedule_item',
        'schedule_follow_up',
        'schedule_outcome',
        'buffer_rule',
        'price_record',
        'intent_issue',
        'place_alias',
        'planning_preference',
        'reminder_preference',
        'notification_outbox'
    ];
    legacy_tables CONSTANT TEXT[] := ARRAY[
        'task',
        'place',
        'geofence_rule',
        'reminder',
        'location_event',
        'reminder_delivery',
        'item',
        'item_place',
        'schedule_item',
        'schedule_follow_up',
        'schedule_outcome',
        'buffer_rule',
        'price_record',
        'intent_issue',
        'line_message_log',
        'conversation_context',
        'place_alias',
        'planning_preference',
        'reminder_preference'
    ];
BEGIN
    FOREACH table_name IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I FOR ALL USING (app_workspace_matches(workspace_id)) '
            'WITH CHECK (app_workspace_matches(workspace_id))',
            'rls_' || table_name || '_workspace',
            table_name
        );
    END LOOP;

    FOREACH table_name IN ARRAY legacy_tables LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN workspace_id DROP DEFAULT', table_name);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN created_by_user_id DROP DEFAULT', table_name);
    END LOOP;
END $$;

-- Private conversation data is isolated by actor as well as household/workspace.
ALTER TABLE line_message_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE line_message_log FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_line_message_log_actor ON line_message_log
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE conversation_context ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_context FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conversation_context_actor ON conversation_context
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

-- Decision traces belong to the requesting actor. SYSTEM receives only the access required
-- for global privacy retention updates/deletes.
ALTER TABLE intent_decision_trace ENABLE ROW LEVEL SECURITY;
ALTER TABLE intent_decision_trace FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_intent_trace_actor_select ON intent_decision_trace
    FOR SELECT
    USING ((app_workspace_matches(workspace_id) AND app_actor_matches(actor_id))
        OR app_current_scope() = 'SYSTEM');
CREATE POLICY rls_intent_trace_actor_insert ON intent_decision_trace
    FOR INSERT
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(actor_id));
CREATE POLICY rls_intent_trace_system_update ON intent_decision_trace
    FOR UPDATE
    USING (app_current_scope() = 'SYSTEM')
    WITH CHECK (app_current_scope() = 'SYSTEM');
CREATE POLICY rls_intent_trace_system_delete ON intent_decision_trace
    FOR DELETE
    USING (app_current_scope() = 'SYSTEM');

-- Idempotency records are private to the actor/request channel. Only SYSTEM retention can
-- remove expired rows across workspaces.
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_idempotency_actor_select ON idempotency_record
    FOR SELECT
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(actor_user_id));
CREATE POLICY rls_idempotency_actor_insert ON idempotency_record
    FOR INSERT
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(actor_user_id));
CREATE POLICY rls_idempotency_actor_update ON idempotency_record
    FOR UPDATE
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(actor_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(actor_user_id));
CREATE POLICY rls_idempotency_system_delete ON idempotency_record
    FOR DELETE
    USING (app_current_scope() = 'SYSTEM');

-- Authentication may append a denied-access audit event but cannot read it back. Tenant
-- requests see their own workspace audit metadata; SYSTEM can perform retention cleanup.
ALTER TABLE security_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE security_audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_security_audit_select ON security_audit_event
    FOR SELECT
    USING (app_workspace_matches(workspace_id) OR app_current_scope() = 'SYSTEM');
CREATE POLICY rls_security_audit_insert ON security_audit_event
    FOR INSERT
    WITH CHECK (app_current_scope() IN ('AUTHENTICATION', 'SYSTEM')
        OR app_workspace_matches(workspace_id));
CREATE POLICY rls_security_audit_system_delete ON security_audit_event
    FOR DELETE
    USING (app_current_scope() = 'SYSTEM');
