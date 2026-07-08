-- 啟用 PostGIS 空間擴充,供後續地點/geofence 的空間查詢使用。
-- 注意:執行帳號需有建 extension 的權限(compose 的 superuser 與 postgis 官方映像皆可)。
CREATE EXTENSION IF NOT EXISTS postgis;
