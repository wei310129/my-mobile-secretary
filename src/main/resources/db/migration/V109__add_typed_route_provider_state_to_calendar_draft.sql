ALTER TABLE calendar_intent_draft
    ADD COLUMN route_provider_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN route_time_role VARCHAR(20),
    ADD COLUMN route_provider VARCHAR(20),
    ADD COLUMN route_evidence_retrieved_at TIMESTAMPTZ;

ALTER TABLE calendar_intent_draft
    ADD CONSTRAINT chk_calendar_intent_draft_route_provider_state
    CHECK (
        route_provider_status IN ('NOT_REQUESTED', 'UNAVAILABLE', 'RETAINED', 'AVAILABLE')
        AND (route_time_role IS NULL OR route_time_role IN ('DEPART_AT', 'ARRIVE_BY'))
        AND (route_provider IS NULL OR route_provider IN ('TDX', 'GOOGLE'))
        AND (
            (route_provider_status = 'NOT_REQUESTED'
                AND route_provider IS NULL
                AND route_evidence_retrieved_at IS NULL)
            OR (route_provider_status IN ('UNAVAILABLE', 'RETAINED')
                AND transport_mode IS NOT NULL
                AND route_time_role IS NOT NULL
                AND route_provider IS NULL
                AND route_evidence_retrieved_at IS NULL)
            OR (route_provider_status = 'AVAILABLE'
                AND transport_mode IS NOT NULL
                AND route_time_role IS NOT NULL
                AND route_provider IS NOT NULL
                AND route_evidence_retrieved_at IS NOT NULL)
        )
    );
