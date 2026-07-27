ALTER TABLE calendar_task_binding
    ADD CONSTRAINT uq_calendar_task_binding_result_identity
        UNIQUE (id, task_id, plan_id, workspace_id, created_by_user_id);

ALTER TABLE planning_preference
    ADD CONSTRAINT uq_planning_preference_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

ALTER TABLE task_reminder_rule
    ADD CONSTRAINT uq_task_reminder_rule_result_identity
        UNIQUE (id, task_id, workspace_id, created_by_user_id);

ALTER TABLE calendar_reminder_rule
    ADD CONSTRAINT uq_calendar_reminder_rule_result_identity
        UNIQUE (
            id, node_id, plan_id, workspace_id, created_by_user_id);

ALTER TABLE place
    ADD CONSTRAINT uq_place_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

ALTER TABLE buffer_rule
    ADD COLUMN explicit_buffer_minutes INTEGER,
    ADD COLUMN explicit_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN explicit_updated_at TIMESTAMPTZ,
    ADD CONSTRAINT uq_buffer_rule_result_identity
        UNIQUE (id, place_id, workspace_id, created_by_user_id),
    ADD CONSTRAINT fk_buffer_rule_owned_place
        FOREIGN KEY (place_id, workspace_id, created_by_user_id)
        REFERENCES place (id, workspace_id, created_by_user_id),
    ADD CONSTRAINT chk_buffer_rule_explicit_policy CHECK (
        (explicit_revision = 0
            AND explicit_buffer_minutes IS NULL
            AND explicit_updated_at IS NULL)
        OR (explicit_revision > 0
            AND explicit_buffer_minutes IS NOT NULL
            AND explicit_buffer_minutes BETWEEN 0 AND 1440
            AND explicit_updated_at IS NOT NULL));

DROP POLICY rls_buffer_rule_workspace ON buffer_rule;
CREATE POLICY rls_buffer_rule_actor
    ON buffer_rule FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE TABLE knowledge_materialization (
    id UUID PRIMARY KEY,
    source_kind VARCHAR(20) NOT NULL,
    fact_binding_id UUID,
    annotation_binding_id UUID,
    plan_id UUID NOT NULL,
    consented_binding_revision BIGINT NOT NULL,
    consented_source_updated_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(30) NOT NULL,
    conversation_scope_hash VARCHAR(64) NOT NULL,
    target_kind VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    row_revision BIGINT NOT NULL,
    command_snapshot VARCHAR(4000) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    proposal_request_hash VARCHAR(64) NOT NULL,
    proposal_payload_hash VARCHAR(64) NOT NULL,
    resolution_kind VARCHAR(20),
    resolution_request_hash VARCHAR(64),
    resolution_payload_hash VARCHAR(64),
    task_id BIGINT,
    calendar_task_binding_id UUID,
    node_id UUID,
    planning_preference_id INTEGER,
    task_reminder_rule_id UUID,
    calendar_reminder_rule_id UUID,
    buffer_rule_id BIGINT,
    buffer_rule_place_id BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_knowledge_materialization_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_knowledge_materialization_proposal_request
        UNIQUE (workspace_id, created_by_user_id, proposal_request_hash),
    CONSTRAINT fk_knowledge_materialization_fact_binding
        FOREIGN KEY (
            fact_binding_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_knowledge_fact_binding (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_annotation_binding
        FOREIGN KEY (
            annotation_binding_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_knowledge_annotation_binding (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_task_binding
        FOREIGN KEY (
            calendar_task_binding_id, task_id, plan_id,
            workspace_id, created_by_user_id)
        REFERENCES calendar_task_binding (
            id, task_id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_preference
        FOREIGN KEY (
            planning_preference_id, workspace_id, created_by_user_id)
        REFERENCES planning_preference (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_task_reminder
        FOREIGN KEY (
            task_reminder_rule_id, task_id, workspace_id, created_by_user_id)
        REFERENCES task_reminder_rule (
            id, task_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_calendar_reminder
        FOREIGN KEY (
            calendar_reminder_rule_id, node_id, plan_id,
            workspace_id, created_by_user_id)
        REFERENCES calendar_reminder_rule (
            id, node_id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_knowledge_materialization_buffer_rule
        FOREIGN KEY (
            buffer_rule_id, buffer_rule_place_id,
            workspace_id, created_by_user_id)
        REFERENCES buffer_rule (
            id, place_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_knowledge_materialization_source CHECK (
        (source_kind = 'FACT'
            AND fact_binding_id IS NOT NULL
            AND annotation_binding_id IS NULL)
        OR (source_kind = 'ANNOTATION'
            AND fact_binding_id IS NULL
            AND annotation_binding_id IS NOT NULL)),
    CONSTRAINT chk_knowledge_materialization_status CHECK (
        status IN (
            'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_knowledge_materialization_target CHECK (
        target_kind IN (
            'TASK', 'CALENDAR_NODE',
            'PLANNING_PREFERENCE', 'BUFFER_RULE',
            'TASK_REMINDER', 'CALENDAR_REMINDER')),
    CONSTRAINT chk_knowledge_materialization_revision CHECK (
        row_revision > 0 AND consented_binding_revision > 0),
    CONSTRAINT chk_knowledge_materialization_scope CHECK (
        channel ~ '^[A-Z][A-Z0-9_]{0,29}$'
        AND conversation_scope_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_knowledge_materialization_resolution CHECK (
        (status = 'PENDING_CONFIRMATION'
            AND resolution_kind IS NULL
            AND resolution_request_hash IS NULL
            AND resolution_payload_hash IS NULL)
        OR (status = 'COMPLETED'
            AND resolution_kind = 'CONFIRM'
            AND resolution_request_hash IS NOT NULL
            AND resolution_payload_hash IS NOT NULL)
        OR (status = 'CANCELED'
            AND resolution_kind = 'CANCEL'
            AND resolution_request_hash IS NOT NULL
            AND resolution_payload_hash IS NOT NULL)
        OR (status = 'EXPIRED'
            AND resolution_kind = 'EXPIRE'
            AND resolution_request_hash IS NULL
            AND resolution_payload_hash IS NULL)),
    CONSTRAINT chk_knowledge_materialization_result CHECK (
        (status IN ('PENDING_CONFIRMATION', 'CANCELED', 'EXPIRED')
            AND task_id IS NULL
            AND calendar_task_binding_id IS NULL
            AND node_id IS NULL
            AND planning_preference_id IS NULL
            AND task_reminder_rule_id IS NULL
            AND calendar_reminder_rule_id IS NULL
            AND buffer_rule_id IS NULL
            AND buffer_rule_place_id IS NULL)
        OR (status = 'COMPLETED' AND (
            (target_kind = 'TASK'
                AND task_id IS NOT NULL
                AND calendar_task_binding_id IS NOT NULL
                AND node_id IS NULL
                AND planning_preference_id IS NULL
                AND task_reminder_rule_id IS NULL
                AND calendar_reminder_rule_id IS NULL
                AND buffer_rule_id IS NULL
                AND buffer_rule_place_id IS NULL)
            OR (target_kind = 'CALENDAR_NODE'
                AND task_id IS NULL
                AND calendar_task_binding_id IS NULL
                AND node_id IS NOT NULL
                AND planning_preference_id IS NULL
                AND task_reminder_rule_id IS NULL
                AND calendar_reminder_rule_id IS NULL
                AND buffer_rule_id IS NULL
                AND buffer_rule_place_id IS NULL)
            OR (target_kind = 'PLANNING_PREFERENCE'
                AND task_id IS NULL
                AND calendar_task_binding_id IS NULL
                AND node_id IS NULL
                AND planning_preference_id IS NOT NULL
                AND task_reminder_rule_id IS NULL
                AND calendar_reminder_rule_id IS NULL
                AND buffer_rule_id IS NULL
                AND buffer_rule_place_id IS NULL)
            OR (target_kind = 'BUFFER_RULE'
                AND task_id IS NULL
                AND calendar_task_binding_id IS NULL
                AND node_id IS NULL
                AND planning_preference_id IS NULL
                AND task_reminder_rule_id IS NULL
                AND calendar_reminder_rule_id IS NULL
                AND buffer_rule_id IS NOT NULL
                AND buffer_rule_place_id IS NOT NULL)
            OR (target_kind = 'TASK_REMINDER'
                AND task_id IS NOT NULL
                AND calendar_task_binding_id IS NOT NULL
                AND node_id IS NULL
                AND planning_preference_id IS NULL
                AND task_reminder_rule_id IS NOT NULL
                AND calendar_reminder_rule_id IS NULL
                AND buffer_rule_id IS NULL
                AND buffer_rule_place_id IS NULL)
            OR (target_kind = 'CALENDAR_REMINDER'
                AND task_id IS NULL
                AND calendar_task_binding_id IS NULL
                AND node_id IS NOT NULL
                AND planning_preference_id IS NULL
                AND task_reminder_rule_id IS NULL
                AND calendar_reminder_rule_id IS NOT NULL
                AND buffer_rule_id IS NULL
                AND buffer_rule_place_id IS NULL)
        ))),
    CONSTRAINT chk_knowledge_materialization_time CHECK (
        expires_at > created_at),
    CONSTRAINT chk_knowledge_materialization_hashes CHECK (
        command_hash ~ '^[0-9a-f]{64}$'
        AND proposal_request_hash ~ '^[0-9a-f]{64}$'
        AND proposal_payload_hash ~ '^[0-9a-f]{64}$'
        AND (resolution_request_hash IS NULL
            OR resolution_request_hash ~ '^[0-9a-f]{64}$')
        AND (resolution_payload_hash IS NULL
            OR resolution_payload_hash ~ '^[0-9a-f]{64}$'))
);

CREATE UNIQUE INDEX uq_knowledge_materialization_resolution_request
    ON knowledge_materialization (
        workspace_id, created_by_user_id, resolution_request_hash)
    WHERE resolution_request_hash IS NOT NULL;
CREATE INDEX idx_knowledge_materialization_source
    ON knowledge_materialization (
        workspace_id, created_by_user_id, source_kind,
        fact_binding_id, annotation_binding_id, created_at);
CREATE INDEX idx_knowledge_materialization_pending_scope
    ON knowledge_materialization (
        workspace_id, created_by_user_id, conversation_scope_hash, expires_at)
    WHERE status = 'PENDING_CONFIRMATION';

ALTER TABLE knowledge_materialization ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_materialization FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_knowledge_materialization_actor
    ON knowledge_materialization FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
