-- Numbered knowledge results must survive one follow-up turn without trusting an LLM-supplied id.
ALTER TABLE conversation_context
    ADD COLUMN last_object_annotation_list_ids VARCHAR(2000),
    ADD COLUMN pending_object_annotation_delete_id BIGINT;

-- Chat deletion is recoverable: keep the private record as a tombstone and hide it from queries.
ALTER TABLE object_annotation
    ADD COLUMN archived_at TIMESTAMPTZ;

CREATE INDEX idx_object_annotation_active_actor
    ON object_annotation (workspace_id, created_by_user_id, created_at DESC)
    WHERE archived_at IS NULL;
