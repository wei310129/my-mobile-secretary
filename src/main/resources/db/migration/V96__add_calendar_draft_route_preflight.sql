ALTER TABLE calendar_intent_draft
    ADD COLUMN route_preflight_status VARCHAR(30),
    ADD COLUMN route_preflight_hash CHAR(64),
    ADD CONSTRAINT chk_calendar_intent_draft_route_preflight CHECK (
        (route_preflight_status IS NULL AND route_preflight_hash IS NULL)
        OR (route_preflight_status IN ('ROUTE_RISK', 'INSUFFICIENT_EVIDENCE')
            AND route_preflight_hash ~ '^[0-9a-f]{64}$'));
