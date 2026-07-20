-- Knowledge records are durable, continuously editable documents without a completion state.
ALTER TABLE object_annotation
    ADD COLUMN updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

UPDATE object_annotation
SET updated_at = COALESCE(archived_at, created_at)
WHERE updated_at IS NULL;

ALTER TABLE object_annotation
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX idx_object_annotation_active_updated
    ON object_annotation (workspace_id, created_by_user_id, updated_at DESC)
    WHERE archived_at IS NULL;
