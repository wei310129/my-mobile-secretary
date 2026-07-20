-- Evolve existing unit-price history into an auditable consumption line without changing its API.
ALTER TABLE price_record
    ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN total_price_twd INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN expense_category VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN semantic_tags VARCHAR(1000) NOT NULL DEFAULT '';

UPDATE price_record
SET total_price_twd = price_twd
WHERE total_price_twd = 0;

ALTER TABLE price_record
    ADD CONSTRAINT chk_price_record_quantity CHECK (quantity BETWEEN 1 AND 10000),
    ADD CONSTRAINT chk_price_record_total CHECK (total_price_twd > 0),
    ADD CONSTRAINT chk_price_record_category CHECK (expense_category IN (
        'FOOD', 'BEVERAGE', 'HOUSEHOLD', 'EDUCATION', 'CHILDCARE', 'ENTERTAINMENT',
        'TRANSPORT', 'HEALTHCARE', 'CLOTHING', 'LUXURY', 'ELECTRONICS', 'HOUSING',
        'WORK', 'OTHER', 'UNKNOWN'));

CREATE INDEX idx_price_record_purchase_date
    ON price_record (workspace_id, created_by_user_id, purchased_at DESC);
CREATE INDEX idx_price_record_store_date
    ON price_record (workspace_id, created_by_user_id, store_name, purchased_at DESC);
