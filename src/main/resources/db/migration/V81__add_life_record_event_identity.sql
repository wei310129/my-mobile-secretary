ALTER TABLE tagged_life_record
    ADD COLUMN source_event_key_hash VARCHAR(64),
    ADD COLUMN source_payload_hash VARCHAR(64),
    ADD CONSTRAINT chk_tagged_life_record_event_identity CHECK (
        (
            source_event_key_hash IS NULL
            AND source_payload_hash IS NULL
        )
        OR (
            source_event_key_hash ~ '^[0-9a-f]{64}$'
            AND source_payload_hash ~ '^[0-9a-f]{64}$'
        )
    ),
    ADD CONSTRAINT uq_tagged_life_record_event_identity
        UNIQUE (
            workspace_id,
            created_by_user_id,
            source_event_key_hash
        );
