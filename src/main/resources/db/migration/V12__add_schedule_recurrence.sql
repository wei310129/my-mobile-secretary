-- 固定行程(使用者 2026-07-15 回饋:一般行事曆的基本功能):
-- 每週重複的行程結束後由 worker 自動排下一週。
-- NONE / WEEKLY(先支援每週;其他週期等真實需求出現再加)
ALTER TABLE schedule_item
    ADD COLUMN recurrence VARCHAR(20) NOT NULL DEFAULT 'NONE';

-- rollover worker 以「週期 + 結束時間」掃描到期的固定行程
CREATE INDEX idx_schedule_item_recurrence_end ON schedule_item (recurrence, end_at);
