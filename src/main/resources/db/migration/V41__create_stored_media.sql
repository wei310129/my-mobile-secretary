CREATE TABLE stored_media (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    source_type        VARCHAR(20)  NOT NULL,
    media_kind         VARCHAR(20)  NOT NULL,
    display_name       VARCHAR(255) NOT NULL,
    original_filename  VARCHAR(255),
    media_type         VARCHAR(120) NOT NULL,
    size_bytes         BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256             VARCHAR(64)  NOT NULL,
    storage_key        VARCHAR(300) NOT NULL UNIQUE,
    source_reference   VARCHAR(255),
    status             VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    deleted_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_stored_media_actor_source
        UNIQUE (workspace_id, created_by_user_id, source_type, source_reference)
);

CREATE INDEX idx_stored_media_actor_created
    ON stored_media (workspace_id, created_by_user_id, created_at DESC);
CREATE INDEX idx_stored_media_actor_sha256
    ON stored_media (workspace_id, created_by_user_id, sha256);

ALTER TABLE stored_media ENABLE ROW LEVEL SECURITY;
ALTER TABLE stored_media FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_stored_media_actor ON stored_media FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
