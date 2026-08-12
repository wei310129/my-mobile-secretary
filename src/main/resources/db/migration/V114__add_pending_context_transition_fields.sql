ALTER TABLE conversation_pending_question
    ADD COLUMN workflow_safe_label VARCHAR(120),
    ADD COLUMN interrupted_question_code VARCHAR(120);

ALTER TABLE conversation_pending_question
    ADD CONSTRAINT chk_conversation_pending_question_safe_label CHECK (
        workflow_safe_label IS NULL OR (
            length(btrim(workflow_safe_label)) BETWEEN 1 AND 120
            AND workflow_safe_label !~ E'[\\r\\n]')),
    ADD CONSTRAINT chk_conversation_pending_question_interrupted_code CHECK (
        interrupted_question_code IS NULL OR (
            interrupted_question_code ~ '^[a-z0-9][a-z0-9._-]{0,119}$'
            AND interrupted_question_code NOT IN (
                'conversation.context-target',
                'conversation.new-operation-content')
            AND question_code IN (
                'conversation.context-target',
                'conversation.new-operation-content')));
