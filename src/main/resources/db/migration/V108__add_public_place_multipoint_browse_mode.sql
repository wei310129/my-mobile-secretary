ALTER TABLE public_place_lookup_draft
    DROP CONSTRAINT chk_public_place_lookup_mode;

ALTER TABLE public_place_lookup_draft
    ADD CONSTRAINT chk_public_place_lookup_mode CHECK (
        mode IN ('READ_ONLY', 'READ_ONLY_MULTIPOINT', 'CALENDAR_LOCATION'));

ALTER TABLE public_place_lookup_draft
    DROP CONSTRAINT chk_public_place_lookup_mode_reference;

ALTER TABLE public_place_lookup_draft
    ADD CONSTRAINT chk_public_place_lookup_mode_reference CHECK (
        (mode IN ('READ_ONLY', 'READ_ONLY_MULTIPOINT') AND calendar_draft_id IS NULL)
        OR (mode = 'CALENDAR_LOCATION' AND calendar_draft_id IS NOT NULL));
