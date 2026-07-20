CREATE TABLE semantic_tag (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    canonical_name     VARCHAR(120) NOT NULL,
    normalized_name    VARCHAR(120) NOT NULL,
    kind               VARCHAR(30)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_semantic_tag_actor_name_kind
        UNIQUE (workspace_id, created_by_user_id, normalized_name, kind)
);

CREATE TABLE semantic_tag_alias (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    tag_id             BIGINT       NOT NULL REFERENCES semantic_tag (id) ON DELETE CASCADE,
    alias_name         VARCHAR(120) NOT NULL,
    normalized_alias   VARCHAR(120) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_semantic_tag_alias_actor
        UNIQUE (workspace_id, created_by_user_id, normalized_alias)
);

CREATE TABLE semantic_tag_edge (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    from_tag_id        BIGINT       NOT NULL REFERENCES semantic_tag (id) ON DELETE CASCADE,
    to_tag_id          BIGINT       NOT NULL REFERENCES semantic_tag (id) ON DELETE CASCADE,
    relation_type      VARCHAR(30)  NOT NULL,
    source_type        VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_semantic_tag_edge_distinct CHECK (from_tag_id <> to_tag_id),
    CONSTRAINT uq_semantic_tag_edge_actor
        UNIQUE (workspace_id, created_by_user_id, from_tag_id, to_tag_id, relation_type)
);

CREATE TABLE tagged_life_record (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    record_type        VARCHAR(30)  NOT NULL,
    title              VARCHAR(200) NOT NULL,
    occurred_at        TIMESTAMPTZ  NOT NULL,
    details            TEXT,
    created_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE semantic_tag_binding (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    tag_id             BIGINT       NOT NULL REFERENCES semantic_tag (id) ON DELETE CASCADE,
    target_type        VARCHAR(30)  NOT NULL,
    target_id          BIGINT       NOT NULL,
    source_type        VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_semantic_tag_binding_actor
        UNIQUE (workspace_id, created_by_user_id, tag_id, target_type, target_id)
);

CREATE INDEX idx_semantic_tag_name ON semantic_tag
    (workspace_id, created_by_user_id, normalized_name);
CREATE INDEX idx_semantic_tag_edge_from ON semantic_tag_edge
    (workspace_id, created_by_user_id, from_tag_id);
CREATE INDEX idx_semantic_tag_edge_to ON semantic_tag_edge
    (workspace_id, created_by_user_id, to_tag_id);
CREATE INDEX idx_semantic_tag_binding_tag ON semantic_tag_binding
    (workspace_id, created_by_user_id, tag_id);
CREATE INDEX idx_tagged_life_record_time ON tagged_life_record
    (workspace_id, created_by_user_id, occurred_at DESC);

ALTER TABLE semantic_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE semantic_tag FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_semantic_tag_actor ON semantic_tag FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

ALTER TABLE semantic_tag_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE semantic_tag_alias FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_semantic_tag_alias_actor ON semantic_tag_alias FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

ALTER TABLE semantic_tag_edge ENABLE ROW LEVEL SECURITY;
ALTER TABLE semantic_tag_edge FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_semantic_tag_edge_actor ON semantic_tag_edge FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

ALTER TABLE tagged_life_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE tagged_life_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_tagged_life_record_actor ON tagged_life_record FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

ALTER TABLE semantic_tag_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE semantic_tag_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_semantic_tag_binding_actor ON semantic_tag_binding FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
