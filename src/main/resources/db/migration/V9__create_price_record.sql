-- Phase 3:價格歷史。收據解析出的品項價格逐筆入庫,
-- 供「上次多少錢/哪裡買比較便宜」查詢與未來的購物建議。
CREATE TABLE price_record (
    id           BIGSERIAL PRIMARY KEY,
    -- 對到品項知識庫(名稱吻合才連;對不到留 NULL,價格照存)
    item_id      BIGINT REFERENCES item (id),
    item_name    VARCHAR(100) NOT NULL,
    store_name   VARCHAR(100),
    -- 單價,台幣整數
    price_twd    INT          NOT NULL,
    purchased_at DATE         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- 查詢主路徑:某品項的價格走勢
CREATE INDEX idx_price_record_item_name ON price_record (item_name, purchased_at DESC);
