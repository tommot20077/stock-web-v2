---
created: 2026-11-07
title: Ops dashboard 與 AI Access 後端 API(目前只有前端提案)
area: backend
target: backlog
files:
  - "前端 repo 根/docs/api-contracts/mock-to-real-contract.md"
  - "前端 repo 根/vue-app/src/pages/Ops.vue"
  - "前端 repo 根/vue-app/src/components/settings/SettingsAiAccess.vue"
source: Yuan 2026-11-07 裁決 3C-5;quick-261107-1j4
---

> 前端 repo 根的路徑定義見 `ai-docs/judgment.md` §8。

## 現況(2026-11-07 核對)

- 後端**沒有** `/api/v1/ops/**` 或任何 AI Access 端點,`ErrorCode` 也沒有對應錯誤碼(`grep -rn 'api/v1/ops\|api/v1/ai' --include='*.java'` 無結果)。
- 前端 `docs/api-contracts/mock-to-real-contract.md` 的〈Ops Contract〉與〈AI Access Contract〉兩節是 2026-05 的前端提案,檔首已標明後端未實作;Yuan 2026-11-07 裁決:**維持提案**,不在 v1.0。
- 前端 Ops 頁與 Settings 的 AI Access 頁維持 Preview / Simulated 標示(前端 AGENTS.md 鐵律 6:可見的控制不得靜默 no-op)。
- PROJECT.md〈Out of Scope〉已補一條指向本 todo。

## 要做之前先定的事

1. **Ops**:要暴露哪些營運動作(回補、重算、快取清除…)、誰能觸發(目前只有 `ROLE_ADMIN` 與 `/api/admin/**`)、任務狀態與日誌放哪裡;是否沿用既有 backfill admin 流程而不是另立 `/ops`。
2. **AI Access 需要獨立的安全設計**,至少涵蓋:
   - 金鑰範圍(scope):唯讀 / 可記錄交易;金鑰綁使用者、可撤銷、只顯示一次;
   - 交易權限:AI 代理是否能呼叫 `POST /api/v1/trades`(注意交易語義是「已成交手動紀錄」,不是下單);
   - 稽核:每次工具呼叫寫 AUDIT log(security.md §13),可在 UI 查詢;
   - 與 PROJECT.md Out of Scope 的「AI-assisted trading policy enforcement and broker credential APIs」一起評估。
3. 定案後先改前端契約兩節為「後端已定案」形狀(或改由後端 `ai-docs/` 新契約檔承載),再排 phase。

## 排程

- backlog,不在 v1.0;Phase 5 不處理。新 milestone 規劃時由 Yuan 決定是否排入。
