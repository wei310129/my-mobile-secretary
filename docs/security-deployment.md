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

## SQL injection 防護與資料存取政策

- 預設使用 Spring Data JPA derived query；複雜查詢使用 JPQL `@Query` 與命名參數。
- 禁止把 request、LINE 訊息、LLM 輸出或任何外部資料用字串串接、`formatted` 或 template
  interpolation 放入 SQL。禁止 `Statement`、`createNativeQuery` 與 `JpaSort.unsafe`。
- PostGIS、RLS scope、冪等 reservation、notification outbox 與跨 tenant retention 等 JPA 無法安全或
  原子表達的路徑，才可使用原生查詢或 JDBC，而且每個 runtime value 都必須經 `@Param`、`?` 或
  `PreparedStatement` 綁定。
- `DatabaseAccessSafetyArchitectureTest` 是 allowlist 安全門檻。新增低階資料庫存取時必須先做資安
  review、加入攻擊字串測試，再有意識地更新 allowlist；不可為了讓測試變綠直接放寬規則。

## Prompt injection 防護政策

- system 規則、能力目錄與 structured-output schema 才是可信指令。使用者文字、歷史任務、對話
  上下文、圖片 OCR、RAG 文件與外部工具輸出一律是不可信資料。
- 送進模型前，外部內容必須放在明確且已跳脫的 data boundary；system prompt 必須聲明不得遵循
  資料內的角色切換、規則覆寫、秘密索取或工具執行要求。
- 外部文件的模型輸出在任何資料寫入前，必須再經 deterministic guard、schema/欄位驗證與一般
  authorization。疑似注入時 fail closed，不執行、不寫入，也不把原始攻擊文字寫入 log。
- Prompt 與關鍵字偵測不是完整安全邊界。所有 model-selected action 仍須遵守 capability allowlist、
  workspace/actor 隔離、最小權限、冪等防重與既有 mutation confirmation；高影響操作必須保留人工
  確認。
