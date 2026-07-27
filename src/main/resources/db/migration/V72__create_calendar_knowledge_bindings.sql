ALTER TABLE user_knowledge_fact
    ADD CONSTRAINT uq_user_knowledge_fact_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

ALTER TABLE object_annotation
    ADD CONSTRAINT uq_object_annotation_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id);

CREATE TABLE calendar_knowledge_fact_binding (
    id UUID PRIMARY KEY,
    fact_id BIGINT NOT NULL,
    target_kind VARCHAR(20) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_id UUID,
    source_updated_at TIMESTAMPTZ NOT NULL,
    review_state VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    binding_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_knowledge_fact_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_calendar_knowledge_fact_source
        FOREIGN KEY (fact_id, workspace_id, created_by_user_id)
        REFERENCES user_knowledge_fact (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_fact_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_fact_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_fact_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_knowledge_fact_target
        CHECK (
            (target_kind = 'PLAN' AND activity_id IS NULL AND node_id IS NULL)
            OR (target_kind = 'ACTIVITY' AND activity_id IS NOT NULL AND node_id IS NULL)
            OR (target_kind = 'NODE' AND activity_id IS NULL AND node_id IS NOT NULL)
        ),
    CONSTRAINT chk_calendar_knowledge_fact_review
        CHECK (review_state IN ('CURRENT', 'REVIEW_REQUIRED')),
    CONSTRAINT chk_calendar_knowledge_fact_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_calendar_knowledge_fact_revision
        CHECK (binding_revision > 0),
    CONSTRAINT chk_calendar_knowledge_fact_hashes
        CHECK (
            creation_request_hash ~ '^[0-9a-f]{64}$'
            AND creation_payload_hash ~ '^[0-9a-f]{64}$'
        )
);

CREATE UNIQUE INDEX uq_calendar_knowledge_fact_plan_target
    ON calendar_knowledge_fact_binding (
        fact_id, plan_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'PLAN';
CREATE UNIQUE INDEX uq_calendar_knowledge_fact_activity_target
    ON calendar_knowledge_fact_binding (
        fact_id, activity_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'ACTIVITY';
CREATE UNIQUE INDEX uq_calendar_knowledge_fact_node_target
    ON calendar_knowledge_fact_binding (
        fact_id, node_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'NODE';
CREATE INDEX idx_calendar_knowledge_fact_target
    ON calendar_knowledge_fact_binding (
        workspace_id, created_by_user_id, target_kind, plan_id, activity_id, node_id);

ALTER TABLE calendar_knowledge_fact_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_knowledge_fact_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_knowledge_fact_binding_actor
    ON calendar_knowledge_fact_binding FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );

CREATE TABLE calendar_knowledge_annotation_binding (
    id UUID PRIMARY KEY,
    annotation_id BIGINT NOT NULL,
    target_kind VARCHAR(20) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    node_id UUID,
    source_updated_at TIMESTAMPTZ NOT NULL,
    review_state VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    binding_revision BIGINT NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_knowledge_annotation_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_calendar_knowledge_annotation_source
        FOREIGN KEY (annotation_id, workspace_id, created_by_user_id)
        REFERENCES object_annotation (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_annotation_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_annotation_activity
        FOREIGN KEY (activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_annotation_node
        FOREIGN KEY (node_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_knowledge_annotation_target
        CHECK (
            (target_kind = 'PLAN' AND activity_id IS NULL AND node_id IS NULL)
            OR (target_kind = 'ACTIVITY' AND activity_id IS NOT NULL AND node_id IS NULL)
            OR (target_kind = 'NODE' AND activity_id IS NULL AND node_id IS NOT NULL)
        ),
    CONSTRAINT chk_calendar_knowledge_annotation_review
        CHECK (review_state IN ('CURRENT', 'REVIEW_REQUIRED')),
    CONSTRAINT chk_calendar_knowledge_annotation_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_calendar_knowledge_annotation_revision
        CHECK (binding_revision > 0),
    CONSTRAINT chk_calendar_knowledge_annotation_hashes
        CHECK (
            creation_request_hash ~ '^[0-9a-f]{64}$'
            AND creation_payload_hash ~ '^[0-9a-f]{64}$'
        )
);

CREATE UNIQUE INDEX uq_calendar_knowledge_annotation_plan_target
    ON calendar_knowledge_annotation_binding (
        annotation_id, plan_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'PLAN';
CREATE UNIQUE INDEX uq_calendar_knowledge_annotation_activity_target
    ON calendar_knowledge_annotation_binding (
        annotation_id, activity_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'ACTIVITY';
CREATE UNIQUE INDEX uq_calendar_knowledge_annotation_node_target
    ON calendar_knowledge_annotation_binding (
        annotation_id, node_id, workspace_id, created_by_user_id)
    WHERE target_kind = 'NODE';
CREATE INDEX idx_calendar_knowledge_annotation_target
    ON calendar_knowledge_annotation_binding (
        workspace_id, created_by_user_id, target_kind, plan_id, activity_id, node_id);

ALTER TABLE calendar_knowledge_annotation_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_knowledge_annotation_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_knowledge_annotation_binding_actor
    ON calendar_knowledge_annotation_binding FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
