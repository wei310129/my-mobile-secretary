-- Phase 1 knowledge retrieval stays actor-private and uses bounded normalized-subject prefixes.
ALTER TABLE object_annotation
    ADD COLUMN normalized_subject VARCHAR(240);

UPDATE object_annotation
SET normalized_subject = lower(regexp_replace(
        subject, '[[:space:][:punct:]，。！？：；、・「」『』（）｜]+', '', 'g'));

ALTER TABLE object_annotation
    ALTER COLUMN normalized_subject SET NOT NULL;

CREATE INDEX idx_user_knowledge_fact_actor_subject_prefix
    ON user_knowledge_fact (
        workspace_id, created_by_user_id, normalized_subject text_pattern_ops, updated_at DESC);

CREATE INDEX idx_object_annotation_actor_subject_prefix
    ON object_annotation (
        workspace_id, created_by_user_id, normalized_subject text_pattern_ops, updated_at DESC)
    WHERE archived_at IS NULL;
