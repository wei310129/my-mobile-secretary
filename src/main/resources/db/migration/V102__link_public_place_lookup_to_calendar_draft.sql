ALTER TABLE public_place_lookup_draft
    ADD COLUMN calendar_draft_id UUID,
    ADD COLUMN calendar_draft_revision BIGINT,
    ADD CONSTRAINT fk_public_place_lookup_calendar_draft
        FOREIGN KEY (calendar_draft_id) REFERENCES calendar_intent_draft (id),
    ADD CONSTRAINT chk_public_place_lookup_calendar_reference CHECK (
        (calendar_draft_id IS NULL AND calendar_draft_revision IS NULL)
        OR (calendar_draft_id IS NOT NULL AND calendar_draft_revision > 0)),
    ADD CONSTRAINT chk_public_place_lookup_mode_reference CHECK (
        (mode = 'READ_ONLY' AND calendar_draft_id IS NULL)
        OR (mode = 'CALENDAR_LOCATION' AND calendar_draft_id IS NOT NULL));

CREATE INDEX idx_public_place_lookup_calendar_draft
    ON public_place_lookup_draft (
        workspace_id, created_by_user_id, calendar_draft_id, status)
    WHERE calendar_draft_id IS NOT NULL;
