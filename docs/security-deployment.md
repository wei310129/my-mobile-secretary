# 正式環境資料庫安全設定

## 角色分離

正式環境至少使用兩個 PostgreSQL 角色：

- migration role：由 Flyway 使用，擁有 schema 與 migration DDL 權限。
- runtime role：由 Spring Data/JDBC 使用，只能操作應用資料，必須是
  `NOSUPERUSER NOBYPASSRLS`。

Spring Boot 可用不同憑證連線：

```yaml
spring:
  datasource:
    username: mms_runtime
    password: ${MMS_RUNTIME_DB_PASSWORD}
  flyway:
    user: mms_migration
    password: ${MMS_MIGRATION_DB_PASSWORD}
```

密碼只放部署平台 secret，不寫入 repository、image、環境範例或 log。

## PostgreSQL 權限範例

請由資料庫管理員依實際 database/schema 名稱調整並執行：

```sql
CREATE ROLE mms_migration LOGIN NOSUPERUSER NOBYPASSRLS;
CREATE ROLE mms_runtime LOGIN NOSUPERUSER NOBYPASSRLS;

GRANT CONNECT ON DATABASE mymobilesecretary TO mms_migration, mms_runtime;
GRANT USAGE ON SCHEMA public TO mms_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mms_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mms_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE mms_migration IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mms_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE mms_migration IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mms_runtime;
```

Migration 建立的 tenant tables 已啟用並強制 PostgreSQL Row-Level Security。應用在每個
JPA transaction 開始時寫入 transaction-local `app.scope`、`app.workspace_id` 與
`app.actor_id`；transaction 結束後設定會自動清除，不會由 connection pool 洩漏到下一個
request。

## 啟動防呆

當 `app.security.enabled=true` 時，應用會檢查 `current_user`：

- `rolsuper=true`：拒絕啟動。
- `rolbypassrls=true`：拒絕啟動。
- 找不到目前 role：拒絕啟動。

RLS 是防止漏寫 workspace predicate、錯誤 repository 查詢與跨租戶程式缺陷的第二道防線；
它不取代 API 驗證、workspace membership authorization、參數化 SQL、secret 管理或資料庫網路
隔離。
