ALTER TABLE calendar_intent_draft
    ADD COLUMN route_journey_kind VARCHAR(32);

ALTER TABLE calendar_intent_draft
    ADD CONSTRAINT chk_calendar_intent_draft_route_journey_kind
    CHECK (
        route_journey_kind IS NULL
        OR route_journey_kind IN ('STANDALONE_TRIP', 'ACTIVITY_WITH_TRANSPORT'));
