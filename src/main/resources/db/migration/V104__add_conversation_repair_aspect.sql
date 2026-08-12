ALTER TABLE conversation_repair_draft
    ADD COLUMN repair_aspect VARCHAR(24) NOT NULL DEFAULT 'UNSPECIFIED';

ALTER TABLE conversation_repair_draft
    ALTER COLUMN repair_aspect DROP DEFAULT;

ALTER TABLE conversation_repair_draft
    ADD CONSTRAINT chk_conversation_repair_aspect CHECK (
        repair_aspect IN ('UNSPECIFIED', 'CONTENT', 'DATA', 'PRESENTATION'));
