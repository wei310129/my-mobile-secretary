-- 固定行程可設定含當日的截止日期，避免無期限展開。
ALTER TABLE schedule_item
    ADD COLUMN recurrence_until DATE;

CREATE INDEX idx_schedule_item_recurrence_until_end
    ON schedule_item (recurrence, recurrence_until, end_at);
