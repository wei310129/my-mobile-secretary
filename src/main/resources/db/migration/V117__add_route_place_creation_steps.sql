ALTER TABLE route_place_creation_draft
    ADD COLUMN step VARCHAR(20) NOT NULL DEFAULT 'CONFIRM';

ALTER TABLE route_place_creation_draft
    ALTER COLUMN place_query DROP NOT NULL,
    ALTER COLUMN candidate_name DROP NOT NULL,
    ALTER COLUMN candidate_latitude DROP NOT NULL,
    ALTER COLUMN candidate_longitude DROP NOT NULL;

ALTER TABLE route_place_creation_draft
    DROP CONSTRAINT chk_route_place_creation_text,
    DROP CONSTRAINT chk_route_place_creation_coordinate;

ALTER TABLE route_place_creation_draft
    ADD CONSTRAINT chk_route_place_creation_step CHECK (
        step IN ('OFFER', 'DETAILS', 'CONFIRM')),
    ADD CONSTRAINT chk_route_place_creation_text CHECK (
        length(btrim(requested_alias)) BETWEEN 1 AND 80
        AND (
            (step IN ('OFFER', 'DETAILS')
                AND place_query IS NULL
                AND candidate_name IS NULL)
            OR
            (step = 'CONFIRM'
                AND length(btrim(place_query)) BETWEEN 2 AND 300
                AND length(btrim(candidate_name)) BETWEEN 1 AND 200)
        )),
    ADD CONSTRAINT chk_route_place_creation_coordinate CHECK (
        (step IN ('OFFER', 'DETAILS')
            AND candidate_latitude IS NULL
            AND candidate_longitude IS NULL)
        OR
        (step = 'CONFIRM'
            AND candidate_latitude BETWEEN -90 AND 90
            AND candidate_longitude BETWEEN -180 AND 180));
