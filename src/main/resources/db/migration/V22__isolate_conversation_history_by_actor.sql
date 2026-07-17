-- Conversation state is private to an actor and an entry channel even inside a household.
-- Existing single-user state is retained only for LOCAL compatibility; LINE and REST start
-- with clean contexts instead of inheriting potentially shared legacy references.
ALTER TABLE conversation_context
    ADD COLUMN channel VARCHAR(40) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE conversation_context
    ADD CONSTRAINT chk_conversation_context_channel
        CHECK (channel IN ('AUTHENTICATION', 'REST', 'LINE', 'BACKGROUND', 'LOCAL', 'TEST'));

ALTER TABLE conversation_context
    DROP CONSTRAINT uq_conversation_context_workspace;

ALTER TABLE conversation_context
    ADD CONSTRAINT uq_conversation_context_scope
        UNIQUE (workspace_id, created_by_user_id, channel);

-- The message table already stores the actor in created_by_user_id. This covering index keeps
-- recent-history reads actor-scoped without scanning another household member's rows.
CREATE INDEX idx_line_message_log_workspace_actor_created
    ON line_message_log (workspace_id, created_by_user_id, created_at DESC, id DESC);
