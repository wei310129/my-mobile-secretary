ALTER TABLE calendar_knowledge_fact_binding
    ADD CONSTRAINT uq_calendar_knowledge_fact_binding_owned_plan
        UNIQUE (id, plan_id, workspace_id, created_by_user_id);

ALTER TABLE calendar_knowledge_annotation_binding
    ADD CONSTRAINT uq_calendar_knowledge_annotation_binding_owned_plan
        UNIQUE (id, plan_id, workspace_id, created_by_user_id);

CREATE TABLE calendar_knowledge_excerpt (
    id UUID PRIMARY KEY,
    source_kind VARCHAR(20) NOT NULL,
    fact_binding_id UUID,
    annotation_binding_id UUID,
    plan_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    snapshot_title VARCHAR(240) NOT NULL,
    snapshot_text VARCHAR(2000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    row_revision BIGINT NOT NULL,
    approved_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    creation_request_hash VARCHAR(64) NOT NULL,
    creation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_knowledge_excerpt_owned_identity
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_knowledge_excerpt_request
        UNIQUE (workspace_id, created_by_user_id, creation_request_hash),
    CONSTRAINT fk_calendar_knowledge_excerpt_plan
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_excerpt_fact_binding
        FOREIGN KEY (fact_binding_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_knowledge_fact_binding (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_knowledge_excerpt_annotation_binding
        FOREIGN KEY (
            annotation_binding_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_knowledge_annotation_binding (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_knowledge_excerpt_source CHECK (
        (source_kind = 'FACT'
            AND fact_binding_id IS NOT NULL
            AND annotation_binding_id IS NULL)
        OR (source_kind = 'ANNOTATION'
            AND fact_binding_id IS NULL
            AND annotation_binding_id IS NOT NULL)
    ),
    CONSTRAINT chk_calendar_knowledge_excerpt_version
        CHECK (version_number > 0 AND row_revision > 0),
    CONSTRAINT chk_calendar_knowledge_excerpt_text CHECK (
        length(btrim(snapshot_title)) BETWEEN 1 AND 240
        AND length(btrim(snapshot_text)) BETWEEN 1 AND 2000),
    CONSTRAINT chk_calendar_knowledge_excerpt_status CHECK (
        status IN ('DRAFT', 'APPROVED', 'REVIEW_REQUIRED', 'REVOKED')),
    CONSTRAINT chk_calendar_knowledge_excerpt_timestamps CHECK (
        (status = 'DRAFT' AND approved_at IS NULL AND revoked_at IS NULL)
        OR (status IN ('APPROVED', 'REVIEW_REQUIRED')
            AND approved_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)),
    CONSTRAINT chk_calendar_knowledge_excerpt_hashes CHECK (
        creation_request_hash ~ '^[0-9a-f]{64}$'
        AND creation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_knowledge_excerpt_fact_version
    ON calendar_knowledge_excerpt (
        fact_binding_id, version_number, workspace_id, created_by_user_id)
    WHERE source_kind = 'FACT';
CREATE UNIQUE INDEX uq_calendar_knowledge_excerpt_annotation_version
    ON calendar_knowledge_excerpt (
        annotation_binding_id, version_number, workspace_id, created_by_user_id)
    WHERE source_kind = 'ANNOTATION';
CREATE INDEX idx_calendar_knowledge_excerpt_grant_eligible
    ON calendar_knowledge_excerpt (
        workspace_id, created_by_user_id, plan_id, version_number)
    WHERE status = 'APPROVED';

ALTER TABLE calendar_knowledge_excerpt ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_knowledge_excerpt FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_knowledge_excerpt_actor
    ON calendar_knowledge_excerpt FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
