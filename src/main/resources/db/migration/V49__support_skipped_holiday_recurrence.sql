ALTER TABLE conditional_recurrence_rule
    DROP CONSTRAINT chk_conditional_recurrence_holiday;

ALTER TABLE conditional_recurrence_rule
    ADD CONSTRAINT chk_conditional_recurrence_holiday
        CHECK (holiday_policy IN ('NONE', 'PREVIOUS_BUSINESS_DAY', 'SKIP'));

ALTER TABLE conditional_recurrence_resolution
    DROP CONSTRAINT chk_conditional_resolution_status;

ALTER TABLE conditional_recurrence_resolution
    ADD CONSTRAINT chk_conditional_resolution_status CHECK (status IN (
        'READY', 'SKIPPED', 'WAITING_OFFICIAL_CONFIRMATION',
        'OUTSIDE_RULE_RANGE', 'RULE_NOT_ACTIVE'
    ));
