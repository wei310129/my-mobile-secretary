CREATE TABLE object_annotation (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID          NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID          NOT NULL REFERENCES app_user (id),
    target_type        VARCHAR(40)   NOT NULL,
    target_id          BIGINT        NOT NULL,
    subject            VARCHAR(240)  NOT NULL,
    detail             VARCHAR(1200) NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_object_annotation_target_type CHECK (target_type IN (
        'PRODUCT_OBSERVATION', 'PRICE_RECORD', 'MEDIA', 'ITEM', 'TASK', 'SCHEDULE'
    ))
);

CREATE INDEX idx_object_annotation_target ON object_annotation
    (workspace_id, created_by_user_id, target_type, target_id, created_at DESC);

ALTER TABLE object_annotation ENABLE ROW LEVEL SECURITY;
ALTER TABLE object_annotation FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_object_annotation_actor ON object_annotation FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
