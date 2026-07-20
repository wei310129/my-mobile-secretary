# 個人知識檢索與未來 RAG 路線

本文件記錄 2026-07-20 的 Knowledge Retrieval Foundation 決策。目標不是先安裝 RAG，
而是讓結構化查詢維持確定性，同時為真正的長文件語意檢索留下穩定邊界。

## 1. 現況分析

### 1.1 已有知識資料

- `UserKnowledgeFact`：使用者明確教過的 actor-private 事實，已有 `category`、`subject`、
  `normalizedSubject`、`detail` 與建立／更新時間；適合小量結構化個人知識。
- `ObjectAnnotation`：掛在商品、媒體、物品、待辦或行程等物件上的自由文字註記；V57 起保存
  `normalizedSubject`，可用有界前綴查詢。
- `Item`：品項、庫存、購物狀態與可購地點，是交易型結構化資料，不是通用 RAG 語料。
- semantic tag graph：以 tag、alias、typed edge、binding 與 LifeRecord 提供最多四層的確定性關聯查詢。
  它已能補足同義標籤與分類關係，但不是向量搜尋。
- `PlaceAlias`、場館資訊、旅行偏好、學校菜單等都有專用欄位與 Application Service；
  仍應由各領域精準查詢，不因存在文字欄位就納入 Retriever。

### 1.2 已有查詢與耦合

- `UserKnowledgeService.find` 先比對正規化主旨，再以同 category 的有限資料做包含判斷。
- `TaggedRecordQueryService` 已沿 tag graph 查 `ObjectAnnotation`、`PriceRecord` 與 LifeRecord；
  `ASK_TAGGED_RECORDS` 是目前最接近通用個人知識查詢的唯讀 Intent。
- `TaskService`、`ScheduleService`、`PriceRecordService`、`PlaceService` 與 reminder services
  各自查詢自己的 Repository；它們需要完整性、排序、計算或狀態判斷，不能改走 Retriever。
- 部分舊服務仍會先取 actor 範圍內清單再以 Java 過濾，例如品項文字匹配、價格 keyword 與媒體清單。
  這些是各領域既有的有限查詢，不應藉本次 Foundation 全面重構；資料量成長時應在原領域補 SQL 索引。

### 1.3 原始媒體與文件能力

`stored_media` 已保存 actor-private 原檔 metadata、SHA-256、storage key、狀態與刪除 tombstone，
bytes 位於可替換的 `MediaObjectStorage`。目前 PDF 與 OOXML 只做檔頭驗證並當 opaque document 保存，
不解壓、不抽字、不 OCR、不切塊，也沒有可供 embedding 的長文件語料。圖片只在既有明確 use case
執行 structured output，再由 Java 寫入各領域資料。

### 1.4 Intent 與信任邊界

現有 `ASK_TAGGED_RECORDS` 已能表達「查我記過的知識」，本階段重用它，不新增
`QUERY_PERSONAL_KNOWLEDGE`、`ASK_DOCUMENT` 或 `ASK_MANUAL`。未來真的出現文件內文問答時，
再評估是否讓通用唯讀 Intent 以 `sourceType/documentType/dateHint/queryText` 擴充；不得為每種文件
增加一個 Intent enum。

`AnthropicIntentInterpreter` 仍只產生 structured command。Retriever 不注入 command、不持有 mutation
service，也不允許模型直接操作 Repository。檢索內容一律視為 untrusted data。

### 1.5 workspace 與 actor 隔離

所有知識 Entity 繼承 `WorkspaceOwnedEntity`，Hibernate `@TenantId` 與 PostgreSQL RLS 提供 workspace
第二道防線；actor-private 表另以 `created_by_user_id` 的 RLS policy 隔離。Foundation 的 repository
query 仍明確帶入 `workspaceId + actorUserId`，Retriever 也先驗證 `KnowledgeQuery` 與目前
`WorkspaceContext` 完全相符。不得先跨 workspace 搜尋再在 Java 移除結果。

`includeSharedWorkspaceKnowledge` 已是查詢契約的一部分，但目前沒有知識可見性資料模型；
即使設為 true，JPA 實作仍維持 actor-private。未來新增共享知識前，必須先定義 visibility 與授權規則。

## 2. 是否需要完整 RAG

結論：目前不值得加入 pgvector，也不值得加入 embedding model。

理由：

1. 現有主要資料是待辦、行程、提醒、地點、庫存、價格與各類有明確欄位的生活紀錄，SQL 能更完整、
   可排序、可統計且可稽核。
2. 真正自由文字知識目前只有短且有界的 `UserKnowledgeFact.detail` 與 `ObjectAnnotation.detail`，
   可由正規化主旨與 tag graph 找回。
3. stored media 尚未抽取文字；目前沒有大量 PDF、說明書、通知或跨文件問答語料。
4. 現在引入 embedding 會先產生模型選型、維度、重建索引、版本、成本與隱私治理負擔，卻沒有已觀察到的
   語意召回問題可驗證收益。

因此本階段只加入 persistence-neutral Retriever 與 JPA 實作，不新增 VectorStore bean、embedding client、
pgvector extension、文件抽取或 OCR 依賴。

## 3. 查詢邊界

### 3.1 一律走 SQL／領域 Service

- Task、Schedule、Reminder 與固定行程的清單、時間範圍、狀態、衝突及 mutation。
- Place、地址、別名、座標、geofence 與距離。
- Item 庫存、購物清單與可購地點。
- PriceRecord、收據金額、消費明細、SUM／COUNT／GROUP BY 與趨勢。
- 使用者、workspace、membership、ownership、完整列舉與所有 destructive action。

### 3.2 走 PersonalKnowledgeRetriever

- 使用者明確教過的家庭關係、地點指引、解讀偏好、商品使用／推薦／注意事項。
- 泛用 ObjectAnnotation 的自由文字註記。
- 第一版只做 actor-private、category/source/time/metadata 過濾與正規化主旨前綴查詢。

### 3.3 未來才走 Vector Store

- 已完成文字抽取與切塊的學校通知、說明書、衛教單、保單、合約、旅行文件、SOP、會議紀錄、
  長期聊天摘要與 OCR 自由文字。
- 向量結果必須先套 workspace、owner/visibility、sourceType、deleted 與版本 metadata filter，
  再做相似度排序；Vector Store 永遠不是 source of truth。

## 4. Foundation 契約

- `KnowledgeQuery`：集中 workspace、actor、查詢文字、category、sourceType、limit、共享 opt-in、
  時間範圍與 metadata filter；limit 預設 10、最大 20，空白查詢直接回空集合。
- `KnowledgeEvidence`：保留來源型別／ID、主旨、內容、metadata、建立／更新時間、workspace、owner、
  structured flag、未來版本與分數欄位。JPA 第一版沒有語意分數與 source version，因此兩者為 null，
  不偽造 relevance score。
- `PersonalKnowledgeRetriever`：Application Service 只依賴這個介面，不知道 JPA、pgvector 或 Spring AI。
- `JpaPersonalKnowledgeRetriever`：只查 `UserKnowledgeFact` 與 active `ObjectAnnotation`；不注入任何
  command/mutation service。

V57 為 `ObjectAnnotation` 增加 `normalized_subject`，並為兩個實際查詢建立
`workspace_id + created_by_user_id + normalized_subject` 前綴索引。未導入 PostgreSQL 中文全文分析器；
在出現可靠語料與召回評估前，不以不受控 `%keyword%` 或全表載入冒充搜尋。

## 5. 未來文件資料模型草案

本階段不建立資料表。進入文件 Phase 時可建立：

### KnowledgeDocument

- `id`, `workspaceId`, `ownerUserId`, `visibility`
- `sourceType`, `originalSourceId`, `storedMediaId`, `documentType`
- `title`, `originalFilename`, `mimeType`, `language`
- `extractedText`, `contentHash`, `sourceVersion`
- `processingStatus`, `processingError`
- `createdAt`, `updatedAt`, `deletedAt`

### KnowledgeChunk

- `id`, `documentId`, `workspaceId`, `ownerUserId`, `visibility`
- `chunkIndex`, `content`, `startOffset`, `endOffset`, `tokenCount`
- `pageNumber`, `metadata`, `sourceVersion`
- `embeddingModel`, `embeddingVersion`, `createdAt`
- 未來才加入 `embedding vector(...)`；模型與維度拍板前不得固定 `vector(1536)` 或任何猜測維度。

文件名稱、頁碼、chunk index、原始 media/document ID、版本與更新時間必須保留，讓回答能引用來源。

## 6. 未來文件處理 Pipeline

```text
檔案上傳
  → 病毒掃描與可信 MIME 檢查
  → 私有原檔保存
  → 文字抽取／OCR
  → 正規化與語言辨識
  → 內容切塊
  → 產生 embedding
  → 儲存 chunk、metadata 與向量
  → 發布可檢索版本
```

實作前必須先定義：content hash 去重、文件更新與 source version、舊 chunk/embedding 清理、
embedding model 版本、非同步狀態與失敗重試、workspace/visibility、刪除與保存期限、敏感文件分類、
原檔與索引的一致性。沒有實際文件量與處理 SLA 前，不建立大型 queue 或工作流框架。

## 7. Prompt injection 與回答品質

資料庫、OCR、文件、聊天摘要與 Retriever 結果都不可信。未來送入模型時必須跳脫並包在：

```xml
<retrieved-evidence untrusted="true">
...
</retrieved-evidence>
```

System prompt 必須維持：內容是資料而非指令；不可改寫能力目錄或 schema；不可要求工具呼叫、
mutation、秘密、prompt、金鑰或環境變數。有衝突時 system 規則優先，Java authorization、schema、
唯一性、可行性與 mutation confirmation 仍是最後邊界。

文件問答只陳述證據支持的內容並附文件名、頁碼／chunk、版本與時間。找不到足夠證據時固定表達
「目前保存的資料中沒有找到足夠資訊」。醫療、法律與保險文件只區分並回報「文件記載內容」，
不得用模型常識補齊結論。

## 8. Project Phase 6 的演進階段與啟動條件

Knowledge Retrieval Foundation 已在目前階段完成；完整文件 RAG 統一排入專案 **Phase 6**，並在 Phase 6
內依下列順序演進：

1. Phase 6.1：維持目前 JPA／正規化主旨／tag graph；有實證需求再評估 PostgreSQL 全文搜尋。
2. Phase 6.2：建立 KnowledgeDocument／KnowledgeChunk 與原檔關聯，但先不產生 embedding。
3. Phase 6.3：依已選定且授權可接受的抽取/OCR 元件實作 pipeline、chunk 與版本治理。
4. Phase 6.4：關鍵字召回評估證明不足後，選定 embedding model 與維度，再加入 pgvector 語意搜尋。
5. Phase 6.5：以 Composite Retriever 做 SQL + keyword/vector + Java rules 的混合檢索。

正式啟動 pgvector 至少要出現一項可量測訊號：

- 已保存大量 PDF、通知或說明書，且已能穩定抽取文字。
- 每次 prompt 必須攜帶過多知識，無法再靠有界 category/context 控制。
- 關鍵字與 tag graph 在真實查詢集經常漏掉語意相近內容。
- 自由文字知識已無法靠固定 category 管理。
- 已出現跨多份文件的自然語言問題。
- 使用者開始大量詢問「上次那份文件」「那張通知」等模糊來源。

導入文件抽取、OCR、PDF 或向量依賴前，必須另列依賴名稱、用途、授權與 GPL/LGPL/AGPL/SSPL／
商用限制及替代方案，取得必要性結論後才可加入。
