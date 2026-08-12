ALTER TABLE conversation_voice_profile
    ADD COLUMN response_style VARCHAR(40);

ALTER TABLE conversation_voice_profile
    ADD CONSTRAINT chk_conversation_voice_profile_response_style CHECK (
        response_style IS NULL OR response_style = 'CONCISE_WARM_SECRETARY');
