ALTER TABLE dispatcher_event
    ADD COLUMN payload_purged_at TIMESTAMPTZ;

ALTER TABLE dispatcher_event
    ADD CONSTRAINT chk_dispatcher_event_payload_purge
        CHECK (payload_purged_at IS NULL OR processing_state = 'CONSUMED');

CREATE INDEX idx_dispatcher_event_payload_retention
    ON dispatcher_event (consumed_at)
    WHERE processing_state = 'CONSUMED' AND payload_purged_at IS NULL;
