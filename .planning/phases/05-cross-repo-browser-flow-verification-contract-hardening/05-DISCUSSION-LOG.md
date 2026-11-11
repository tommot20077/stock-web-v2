# Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-07
**Phase:** 05-cross-repo-browser-flow-verification-contract-hardening
**Areas discussed:** 金額字串化的邊界與切換, SC3/SC4 旅程的形狀, SC5 失敗怎麼製造, 設定對齊與 plan 順序

> 前置:範圍已由 `.planning/todos/pending/2026-11-07-phase-5-scope-decisions.md` 鎖定(Yuan 2026-11-07),本次只討論實作方式。

---

## 金額字串化的邊界與切換

**發現:** 行情 DTO(`LatestPriceDto`、`KlineDto`、`PriceTickEvent`)已用 `ToStringSerializer` 輸出字串;trading DTO 仍是數字(`CashEntryDto` 註解明寫刻意不加)。

| 問題 | 選項 | Selected |
|------|------|----------|
| 涵蓋範圍 | 全 API 一致(trading + backtest + asset) | ✓ |
| | 只改 trading | |
| 切換方式 | 硬切,前後端同步合併 | ✓ |
| | 後端先過渡(旗標 / 先改請求端) | |
| 前端 decimal 深度 | 涉及金額的計算一律用 decimal | ✓ |
| | 邊界精確,顯示用 number | |
| | 你決定 | |
| 守門 | 自動測試守門 + 契約文件 | ✓ |
| | 只更新契約文件 | |
| 比率欄位 | 後端是 BigDecimal 的就一律字串 | ✓ |
| | 只有金額 / 數量 / 價格改 | |

**User's choice:** 全部採用建議選項。

---

## SC3/SC4 旅程的形狀

| 問題 | 選項 | Selected |
|------|------|----------|
| Journey 落地 | 新寫 contract-journey.spec.ts | ✓ |
| | 拉長既有 T1 | |
| SC4 斷言位置 | 嵌進同一條 journey | ✓ |
| | 另寫 security spec | |
| 請求斷言程度 | journey 全程監聽所有 /api/v1 請求 | ✓ |
| | 關鍵步驟抽樣 | |
| portfolio reads | Overview + Positions + Trades | ✓ |
| | 加上 Cash | |
| | 只 Positions + Trades | |
| @extended 實作 | run-e2e.sh 預設跑全部 | ✓ |
| | 只改 CI 指令 | |
| E2E 位置 | 兩邊都跑(前端 CI 新增 browser-e2e job) | ✓ |
| | 只在後端 CI 跑 | |

**Notes:**
- 「Journey 落地」第一次回答「什麼意思」:補充說明現有片段分散在 A2 / A3 / T1 等獨立 test,兩種落地方式的差異後,Yuan 選新寫 spec。
- 「E2E 位置」Yuan 問「你覺得呢,要拆開來還是一邊就好」:Claude 建議兩邊都跑(前端單獨 PR 目前沒有真後端驗證;run-e2e.sh 已封裝,成本約 40 行 workflow;若後端 private 需設唯讀 secret),Yuan 採用。

---

## SC5 失敗怎麼製造

| 問題 | 選項 | Selected |
|------|------|----------|
| 製造失敗 | page.route 注入失敗,重試時放行 | ✓ |
| | 真的停掉後端 | |
| | 兩者都要 | |
| 覆蓋頁面 | 所有接真 API 的頁面(6 頁) | ✓ |
| | 只 journey 走過的三頁 | |
| mock 斷言 | 錯誤狀態可見 + 沒有 mock 哨兵值 | ✓ |
| | 只斷言錯誤狀態可見 | |
| 失敗種類(多選) | 網路中斷 | ✓ |
| | 5xx 後端信封 | ✓ |
| | 非信封回應 | ✓ |
| 矩陣 | 分層(約 6 條 + Vitest 補完) | |
| | 全矩陣 18 組 | ✓ |

**Notes:** 矩陣一題 Yuan 選擇多於建議的全矩陣。

---

## 設定對齊與 plan 順序

| 問題 | 選項 | Selected |
|------|------|----------|
| Port | Vite 讀環境變數,預設 11180 | ✓ |
| | 後端 dev 預設改 8080 | |
| | 維持現狀,只寫文件 | |
| TTL | 兩者都對齊 PT15M | ✓ |
| | 兩者都對齊 PT30M | |
| | 刪掉 cookie TTL,沿用 JWT TTL | |
| *E2ETest | 改名為 *E2E.java + 命名守門 | ✓ |
| | 調整 include / exclude | |
| Plan 順序 | 先建安全網,再做金額字串化 | ✓ |
| | 先金額字串化,再寫 E2E | |
| | 你決定 | |
| PR 切法 | 每個 wave 一組 PR | ✓ |
| | 整個 phase 一組 PR | |

**Notes:** TTL 一題附帶說明:cookie Max-Age 15 分鐘,但其中的 JWT 有效 30 分鐘,外洩時可用 30 分鐘。

---

## Claude's Discretion

- SC1 / SC2 稽核表格式與位置、decimal 套件選型、後端字串化實作方式、SC5 mock 哨兵值管理、環境變數名稱、TTL / 命名守門方式、平行小項目歸屬哪組 PR。

## Deferred Ideas

- 無新增;todo 比對中 7 筆非 Phase 5 的 todo 依 Yuan 2026-11-07 裁決列入 Deferred(見 CONTEXT.md)。
