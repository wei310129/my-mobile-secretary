ALTER TABLE calendar_intent_draft
    ADD COLUMN recurrence_rule VARCHAR(30),
    ADD COLUMN recurrence_until DATE,
    ADD CONSTRAINT chk_calendar_intent_draft_recurrence CHECK (
        (recurrence_rule IS NULL AND recurrence_until IS NULL)
        OR recurrence_rule IN (
            'DAILY', 'WEEKDAYS', 'WEEKLY', 'MONTHLY_NTH_WEEKDAY'));
