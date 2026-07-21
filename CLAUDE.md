# Claude Code repository adapter

本檔只提供 Claude Code 的平台入口，不複製完整 repository 規則。

## 啟動順序

1. 先讀根目錄 `AGENTS.md`；其不變量、搜尋限制、輸出控制、Maven 安全入口與繁體中文回報規則同樣適用。
2. 再讀 `docs/agent-context/index.md`，依任務只載入指定文件章節或 repo-scoped skill。
3. 進入 `internal/ai-dispatcher/` 時，套用該目錄的 `AGENTS.md`。
4. 長期或多階段工作遵循 `docs/agent-context/execution-plan-policy.md`。

## Claude Code 平台注意事項

- 使用者背景約三年 Java 後端經驗；以 Java 21、Spring Boot 3.5.x、Spring AI 1.1.x 現行寫法說明。
- 若 Claude Code 不會自動解析 `.agents/skills/` metadata，仍應在符合任務時手動讀取對應 `SKILL.md`。
- 以 repository 文件為 source of truth；不要把本檔擴寫成第二份架構、模組清單、測試政策或產品決策副本。
- 共通規則只修改其權威來源，再由本檔連結；不維護 `AGENTS.md`／`CLAUDE.md` 兩份人工同步內容。

## 常用入口

- 任務路由：`docs/agent-context/index.md`
- 目前決策：`docs/decisions/current.md`
- 執行計畫：`docs/exec-plans/active/`
- 測試策略：`docs/test-strategy.md`
- 架構：`docs/architecture.md`（依標題按需讀取）
- 歷史開發計畫：`docs/development-plan.md`（保留既有連結與追溯）
