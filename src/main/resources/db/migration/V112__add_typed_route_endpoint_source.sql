ALTER TABLE calendar_intent_draft
    ADD COLUMN location_source VARCHAR(40),
    ADD COLUMN transport_origin_source VARCHAR(40);

UPDATE calendar_intent_draft
SET location_source = 'LEGACY_UNSPECIFIED'
WHERE location_label IS NOT NULL;

UPDATE calendar_intent_draft
SET transport_origin_source = 'LEGACY_UNSPECIFIED'
WHERE transport_origin_label IS NOT NULL;

ALTER TABLE calendar_intent_draft
    ADD CONSTRAINT chk_calendar_intent_draft_location_source CHECK (
        (location_label IS NULL AND location_source IS NULL)
        OR (location_label IS NOT NULL AND location_source IN (
            'EXPLICIT_CURRENT_TURN', 'CONFIRMED_HOME',
            'CONFIRMED_CONTEXT', 'LEGACY_UNSPECIFIED'))),
    ADD CONSTRAINT chk_calendar_intent_draft_transport_origin_source CHECK (
        (transport_origin_label IS NULL AND transport_origin_source IS NULL)
        OR (transport_origin_label IS NOT NULL AND transport_origin_source IN (
            'EXPLICIT_CURRENT_TURN', 'CONFIRMED_HOME',
            'CONFIRMED_CONTEXT', 'LEGACY_UNSPECIFIED')));
