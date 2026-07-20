ALTER TABLE user_knowledge_fact
    DROP CONSTRAINT chk_user_knowledge_fact_category;
ALTER TABLE user_knowledge_fact
    ADD CONSTRAINT chk_user_knowledge_fact_category CHECK (category IN (
        'RELATIONSHIP', 'PLACE_GUIDANCE', 'INTERPRETATION_PREFERENCE',
        'PRODUCT_USAGE', 'PRODUCT_RECOMMENDATION', 'PRODUCT_CAUTION'
    ));

CREATE TABLE product_observation_draft (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    product_name VARCHAR(180),
    brand_name VARCHAR(120),
    color_name VARCHAR(120),
    source_title VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_product_observation_status
        CHECK (status IN ('PENDING_PURPOSE', 'RESOLVED'))
);

CREATE INDEX idx_product_observation_actor_status
    ON product_observation_draft (
        workspace_id, created_by_user_id, status, created_at DESC);

ALTER TABLE product_observation_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_observation_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_product_observation_actor ON product_observation_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
