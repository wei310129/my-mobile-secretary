ALTER TABLE schedule_item
    ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE schedule_item
    ADD CONSTRAINT chk_schedule_item_category
        CHECK (category IN ('PERSONAL', 'WORK', 'FAMILY', 'UNKNOWN'));
