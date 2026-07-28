CREATE TABLE project_conversation_focus_binding (
    id UUID PRIMARY KEY,
    conversation_focus_id UUID NOT NULL,
    project_id UUID NOT NULL,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_project_focus_binding_focus
        UNIQUE (conversation_focus_id),
    CONSTRAINT fk_project_focus_binding_focus
        FOREIGN KEY (
            conversation_focus_id, workspace_id, created_by_user_id,
            channel, conversation_scope_digest)
        REFERENCES conversation_focus (
            id, workspace_id, created_by_user_id, channel, conversation_scope_digest),
    CONSTRAINT fk_project_focus_binding_project
        FOREIGN KEY (project_id, workspace_id, created_by_user_id)
        REFERENCES project (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_project_focus_binding_digest
        CHECK (conversation_scope_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_project_focus_binding_project
    ON project_conversation_focus_binding (
        workspace_id, created_by_user_id, project_id);

ALTER TABLE project_conversation_focus_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_conversation_focus_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_project_focus_binding_actor
    ON project_conversation_focus_binding
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    )
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
    );
