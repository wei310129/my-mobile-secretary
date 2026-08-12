ALTER TABLE calendar_intent_draft
    ADD COLUMN transport_origin_label VARCHAR(200),
    ADD COLUMN transport_origin_latitude DOUBLE PRECISION,
    ADD COLUMN transport_origin_longitude DOUBLE PRECISION,
    ADD COLUMN transport_offer_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN activity_adjustability VARCHAR(20),
    ADD COLUMN transport_mode VARCHAR(20),
    ADD COLUMN transport_node_id UUID;

ALTER TABLE calendar_intent_draft
    ADD CONSTRAINT chk_calendar_intent_draft_transport_origin CHECK (
        (transport_origin_label IS NULL
            AND transport_origin_latitude IS NULL
            AND transport_origin_longitude IS NULL)
        OR (length(btrim(transport_origin_label)) BETWEEN 1 AND 200
            AND transport_origin_latitude BETWEEN -90 AND 90
            AND transport_origin_longitude BETWEEN -180 AND 180)),
    ADD CONSTRAINT chk_calendar_intent_draft_transport_offer CHECK (
        transport_offer_status IN ('NONE', 'OFFERED', 'ACCEPTED', 'DECLINED', 'PLANNED')),
    ADD CONSTRAINT chk_calendar_intent_draft_adjustability CHECK (
        activity_adjustability IS NULL
        OR activity_adjustability IN ('LOCKED', 'WINDOWED', 'FLEXIBLE')),
    ADD CONSTRAINT chk_calendar_intent_draft_transport_plan CHECK (
        (transport_offer_status <> 'PLANNED'
            AND transport_node_id IS NULL)
        OR (transport_offer_status = 'PLANNED'
            AND transport_mode IN ('DRIVE', 'TWO_WHEELER', 'WALK', 'TRANSIT')
            AND transport_node_id IS NOT NULL)),
    ADD CONSTRAINT fk_calendar_intent_draft_transport_node
        FOREIGN KEY (transport_node_id, workspace_id, created_by_user_id)
        REFERENCES calendar_time_node (id, workspace_id, created_by_user_id);
