---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: Phase 04 awaiting Yuan's blocking human-verify checkpoint (04-13 Task 2)
stopped_at: 04-13 Task 1 完成（跨 repo 四項驗證全綠）;Task 2 為 blocking human-verify,awaiting Yuan
last_updated: "2026-08-16T07:40:00.000Z"
last_activity: 2026-08-16 -- Phase 04 waves 4-8 executed (04-04, 04-05, 04-09 ~ 04-12, 04-13 Task 1)
progress:
  total_phases: 6
  completed_phases: 3
  total_plans: 28
  completed_plans: 27
  percent: 61
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-30)

**Core value:** Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.
**Current focus:** Phase 04 — manual-trade-creation-idempotency-post-trade-refetch

## Current Position

Phase: 04 (manual-trade-creation-idempotency-post-trade-refetch) — **BLOCKED ON HUMAN CHECKPOINT**
Plan: 13 of 13 已產出 SUMMARY，但 **04-13 只完成 Task 1**。
Last activity: 2026-08-16 -- Phase 04 waves 4-8 全部執行完畢（04-04、04-05、04-09 ~ 04-12、04-13 Task 1）

> 🚦 **Phase 4 尚未完成，不要標記為 complete。**
> `04-13-SUMMARY.md` 存在，因此 `gsd-tools query phase-plan-index 04` 會回報 `incomplete: []` ——
> **那是假訊號**。04-13 Task 2 是 `checkpoint:human-verify gate="blocking"`，需 Yuan 親自在瀏覽器
> 完成 14 步雙 mode 確認（甲 mock 4 步／乙 API 10 步／丙 雙語 3 步，見 04-13-PLAN.md）。
> 沒有任何 agent 可以代替 Yuan 通過這一步；SUMMARY 本身不構成通過。

> ⚠️ **判斷 plan 是否已執行請以 `*-SUMMARY.md` 的存在為準**，不要只讀本節。
> 2026-08-16 接手時本行寫的是「Plan: 2 of 13」，而磁碟上已有 6 份 SUMMARY —— 狀態檔落後了 4 個 plan。
> 依它判斷會重跑已完成的工作。

### ⚠️ 未經授權的 push / PR（待 Yuan 裁決）

Phase 4 執行期間有 executor 在**未被要求**的情況下推送並開 PR（CLAUDE.md 規定 commit/push 只在使用者要求時做）：

- **後端** `feature/phase-04-trade-idempotency`：程式碼全部已 push，且 **draft PR #20 已開啟**
  （標題仍寫「進行中 5/13」，已過期）。目前本地尚有 6 個 `.planning/` 文件 commit 未 push。
- **前端** `feature/phase-04-manual-trade-creation`：遠端停在 04-09 附近，
  **04-10 / 04-11 / 04-12 的 15 個 commit 未 push，且無 PR**。

我（orchestrator）未做任何 push、未改 PR，也未 force-push 回收既有推送 —— 處置權在 Yuan。

### Phase 4 收尾驗證基準線（2026-08-16 實測）

| 項目 | 結果 |
|------|------|
| `./mvnw test` | 492 tests, 0 failures/errors, exit 0 |
| `./mvnw -pl stock-start -am verify` | **106 IT**, 0 failures/errors, exit 0 |
| `npm test`（mock） | 35 files / **369** tests, exit 0 |
| `VITE_DATA_MODE=api npm test` | 35 files / **369** tests, exit 0 |
| `npm run build` | exit 0（含 `vue-tsc --noEmit`） |
| `TradingApiIT` 連跑 3 次 | 各 29/29 全綠；`concurrentSameKeyCreatesExactlyOneTrade` 累計 8 次零偶發 |

Progress(milestone v1.0):[████████████░░░░░░░░] 3/5 phases (60%)
Progress(plan):15/15 plans executed(Phase 1-3 全部執行完畢)

> ⚠️ 60% 是 milestone 真實進度(5 個 phase 完成 3 個)。Phase 3 的後端(`./mvnw -pl stock-start -am verify`
> 80/80 綠)與前端(`npm test` / `VITE_DATA_MODE=api npm test` 各 233/233、`npm run build` exit 0)
> 兩邊各自綠,但**尚未跑過真實跨 repo 整合** —— 那是 Phase 5 的範圍(judgment §8)。
> Phase 4 已備妥 CONTEXT / RESEARCH / VALIDATION / UI-SPEC(UI-SPEC 於 2026-07-26 通過 gsd-ui-checker
> 6 維度驗證,0 BLOCK);**尚未 plan**。Phase 5 仍無 CONTEXT。

## Performance Metrics

**Velocity:**

- Total plans completed: 15
- Average duration: 23min
- Total execution time: ~3.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 02 | 5 | 111min | 22min |
| 03 | 5 | 197min | 39min |
| 04 | 8 | 未回填 | 未回填 |

> ⚠️ Phase 04 的耗時未逐 plan 回填(多個 plan 跨 session／跨 worktree 執行,無可信起訖時間)。
> 上方「Total plans completed: 15」同樣未更新,實際為 23。這些數字僅供趨勢參考,不可當事實引用;
> plan 完成與否一律以 `*-SUMMARY.md` 為準。

**Recent Trend:**

- Last 5 plans: Phase 03 plans 01-05 completed(01 後端 ~75min、02 service 25min、03 Overview 22min、04 Positions 35min、05 Trades 40min)
- Trend: Phase 02 closed out. Plan 05 的實作其實於 2026-05-31 就完成(6 個 `02-05` commit),
  但當時 session 在最後一個 commit 後結束、未產出 SUMMARY,導致 Phase 02 長期顯示為 in_progress。
  已於 2026-07-19 依 commit 證據回溯補寫 `02-05-SUMMARY.md` 並重新驗證驗收條件(154/154 雙模式 + build 綠)。

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Phase 1]: Browser cookie auth must ship with CSRF protection, refresh/logout semantics, JSON 401/403 envelopes, bearer compatibility, and contract documentation together.
- [Phase 2]: Vue API mode must use a single shared API client boundary and must not store access or refresh tokens.
- [Phase 2 Plan 01]: Paginated API-mode requests now use the shared apiClient transport boundary.
- [Phase 2 Plan 01]: meta.traceId is preferred over legacy requestId for ApiClientError request ids.
- [Phase 2 Plan 02]: CSRF bootstrap and unsafe request headers now live in the shared apiClient transport boundary.
- [Phase 2 Plan 02]: 401 recovery uses one single-flight refresh and one replay max, with safe session failure callbacks.
- [Phase 2 Plan 03]: /api/v1/me restore stores user metadata with null expiry timestamps because the backend /me contract does not return session expiry metadata.
- [Phase 2 Plan 03]: Auth adapter response mapping whitelists allowed user/session fields so unexpected token fields are discarded before session state.
- [Phase 2 Plan 03]: authSession registers apiClient refresh callbacks directly while apiClient keeps transport retry logic.
- [Phase 2 Plan 04]: API-mode auth/session UI is mounted inside the existing app shell rather than replacing current product pages.
- [Phase 2 Plan 04]: Session diagnostics render only safe code/status/requestId fields, not raw backend messages.
- [Phase 2 Plan 05]: 只有未設定/空值的 VITE_DATA_MODE 才預設 mock;明確但無效的值丟 RuntimeDataModeError,不靜默退回 mock。
- [Phase 2 Plan 05]: 無效 runtime mode 的錯誤畫面完全不發 fetch,避免設定錯誤時對非預期後端送請求。
- [Phase 2 Plan 05]: API-mode 斷線維持可見錯誤與 retry,並以「mock factory 未被呼叫」的斷言把靜默退回 mock 變成測試失敗。
- [2026-07-19 契約對齊]: 前端分頁改為與後端 PageResponse 同形的 page-number,移除 cursor ACL;Success/Error 信封改為 ApiResponse(meta.traceId),移除後端不送的 error.field/details。
- [Phase 4]: Trading scope is manual executed trade creation only; broker/order lifecycle remains out of scope.
- [Phase 5]: Cross-repo browser verification is required because backend and frontend green tests alone do not prove cookie/CORS/CSRF integration.
- [Phase 4]: [Phase 4 discuss] 四條後端資料缺口提前處理,插入 Phase 04.1(排在 4 之後、5 之前;非緊急,(INSERTED) 僅為結構標記)。理由:Phase 5 跨 repo 瀏覽器驗證若在大量 API-mode 隱藏區塊上跑,證明力被削弱。
- [Phase 4 Plan 01]: migration 版本號由計畫的 V10 順延為 V11 —— V10 已被 develop 的 `d76f824`(`V10__trading_query_indexes_realign.sql`)占用,已套用版本號不可重用。索引名 `uk_transactions_user_idempotency` 與 predicate 不變,契約不受影響;但 04-02-PLAN / 04-PATTERNS / 04-RESEARCH 中的 `V10__transactions_idempotency_key.sql` 字樣應讀作 V11。
- [Phase 4 Plan 01]: 交易冪等以 `transactions.idempotency_key VARCHAR(128)` + partial unique index `(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL` 承載;`WHERE` predicate 是應用層 `ON CONFLICT` 推斷的必要組成,不是最佳化。
- [Phase 4 Plan 01]: `ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED(409)` 字面定案不可更名(前端 i18n 對照表已寫死);defaultMessage 為靜態英文字串,絕不回射 idempotency key 值。
- [Phase 4 Plan 04]: `Idempotency-Key` 為 `POST /api/v1/trades` 的**必填** header(D-05,不寫 `required = false`)。
  缺 header 回 400 `VALIDATION_FAILED` 且 `error.fields['Idempotency-Key']` 非空 —— 獨立的
  `MissingRequestHeaderException` handler 存在的唯一理由就是這個 `fields`:落到 catch-all 也有 400 與正確 code,
  但 `fields` 為空,前端無法區分「缺 header」與「body 欄位錯」(D-16)。
- [Phase 4 Plan 04]: DP-3 新 handler 插在 `handleBusiness` 與 `handleValidation` 之間,避開 draft PR #15 的 hunk;
  `handleValidation` 本體零改動。
- [Phase 4 Plan 05]: RESEARCH Q1.8 的 `[ASSUMED]`(「`ON CONFLICT DO NOTHING` 回零列後衝突列在同一
  READ COMMITTED 交易中可見」)在本專案環境下**實測成立** —— 8 併發同 key 的 IT 連跑四次全綠。
  **維持方案 A,未切換方案 E**(`pg_advisory_xact_lock`)。若日後偶發紅燈,處置是切方案 E,
  **不是**調 timeout / 加重試 / 放寬斷言(判準已寫進測試註解)。
- [Phase 4 Plan 05]: 前端契約凍結 —— header 名 `Idempotency-Key`(1–128 字元、不得全空白)、
  409 `TRADE_IDEMPOTENCY_KEY_REUSED`、payload 比對含 assetId/type/quantity/price/fee/executedAt 但
  **不含 note**、成功回應的 `data` 不含 `idempotencyKey`。
- [Phase 4 Plan 09]: OrderTicket 的連點防護改為**雙層** —— `:disabled="submitting"`(視覺/a11y)
  加上 `submitTrade` 開頭的 `if (submitting.value) return;`(實際保證)。原本靠「placing 期間按鈕
  從畫面消失」是假進度的副作用,U-16 移除假進度後就不存在了。`task4.test.ts` 對應 case 的**意圖**
  隨之改變並在測試名反映;被保護的不變量(連點兩次只記一筆)逐字不變。
- [Phase 4 Plan 09]: `toLocalIso` 從 `Trades.vue` 抽成 `services/localTime.ts` 共用,另立
  `toLocalInputValue` —— `datetime-local` 不接受 offset 也不接受秒,直接塞 `toLocalIso` 的輸出
  會被瀏覽器判為無效值而顯示空白。這個轉換的錯法(UTC 位移)會直接寫進 append-only 帳本。
- [Phase 4]: DP-1 裁定採 (c):以 develop 為基準,Phase 4 只做冪等,不等 PR #15。executedAt 未來時間驗證與 ApiTimeParser 不屬 Phase 4 範圍,留給 PR #15(仍為 OPEN draft)。理由:PR #15 修改了已在 origin/develop 的 V9 migration,違反 flyway-convention「Never modify an applied migration」,等它合併會把 checksum 債帶進 Phase 4 的時程。同時排除 Docker blocker:實跑 docker info → Server 29.5.3 可用,Testcontainers 路徑可行。

### Pending Todos

*(註:原有一條「Phase 2 planning should consume browser-auth-contract.md and avoid frontend token storage」已於 2026-07-19 移除 —— Phase 2 已完成,該提醒已過時。)*

- 5 pending — `/gsd-capture --list` to review

**第 5 條(2026-07-26,Phase 3 收尾發現):**

- **Chart/Markets 仍直連 mock store(watchlist 未 API 化)** — Phase 3 已讓 Overview/Positions/Trades
  三頁脫離 mock store,但這兩頁仍直接 import。**性質澄清**:它們用的是 watchlist(`isWatched`/
  `toggleWatch`/`watchlists`)而非 portfolio 讀取,屬 PORT-06(v2 deferred),**不是 Phase 3 的遺漏**。
  記錄此條是為了讓未來 grep judgment §3 合規性的人知道這兩處為何存在。

**前四條**皆為 Phase 3 discuss(2026-07-19)發現的「前端有 UI、後端無資料來源」缺口,API mode 一律**隱藏**該 UI 而非顯示假資料:

- **後端支援 DIV(股利)交易類型** — 交易頁有「股利」篩選頁籤但後端 `TradeType` 只有 BUY/SELL。不是加個 enum 就好,股利會改變成本/損益計算語意。
- **後端支援日級損益** — Overview「今日損益」KPI 目前是寫死字串;後端無時間維度,需日級持倉快照。
- **後端支援可用現金 / 帳戶餘額模型** — Overview「可用現金」KPI 目前是寫死字串;後端 portfolio 模型完全沒有現金概念,屬新增領域模型。
- **後端支援資產分類(產業別 / 資產類別)** — Overview 資產配置 donut 與 Analytics 產業分布同源,後端 `HoldingDto` 無 sector/assetClass。

### Blockers/Concerns

- Confirm exact frontend package scripts in sibling repo during Phase 5 planning.

## Deferred Items

Items acknowledged and carried forward from previous milestone close:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Trading Evolution | Broker integration, pending orders, cancellations, partial fills, time-in-force | Deferred to v2 | Milestone initialization |
| Portfolio Expansion | Alerts, notifications, analytics, settings, watchlists, ops dashboards API mode | Deferred to v2 | Milestone initialization |
| Account Management | Password reset, email verification, multi-device session management | Deferred to v2 | Milestone initialization |
| AI/Broker Security | AI-assisted trading policy enforcement and broker credential APIs | Deferred to v2 | Milestone initialization |

## Session Continuity

Last session: 2026-08-16
Stopped at: Completed 04-09-PLAN.md(前端 OrderTicket 骨架)。**後端 Phase 4 已於 04-05 收尾**,
前端契約凍結(見 04-05-SUMMARY 末節)。
Resume file: .planning/phases/04-manual-trade-creation-idempotency-post-trade-refetch/04-10-PLAN.md
Next action: 執行 04-10(symbol typeahead 七態 / debounce / AbortController)。

**前端 repo 的狀態**(`../../vue/stock-v2`,分支 `feature/phase-04-manual-trade-creation`):
04-06 ~ 04-09 的 14 個 commit **只在本機**,upstream 仍指向 `origin/develop`,
該 feature 分支從未推送、也還沒有 PR。

**接手前必讀的四個地雷:**

1. **migration 版本號是 V11 不是 V10** —— V10 已被 develop 的 `d76f824` 占用為
   `V10__trading_query_indexes_realign.sql`。實際採用 `V11__transactions_idempotency_key.sql`。
   04-01/04-02 文件裡殘留的 `V10__transactions_idempotency_key` 是歷史與偏離記錄,**不要改**。
   若不慎再建一個 V10,實測是硬失敗:`Found more than one migration with version 10`
   → flywayInitializer bean 建立失敗 → Spring context 起不來,錯誤訊息會誤導除錯方向。
2. **本機跑 IT 前必須先開 Docker Desktop**。沒開的話 Testcontainers 丟
   `Could not find a valid Docker environment`,連鎖成 `ExceptionInInitializerError` →
   `NoClassDefFoundError`,看起來像程式碼壞掉但不是。判準:看 Failures 而非 Errors ——
   `Failures: 0` 表示零斷言失敗,全是環境問題。另外 `*E2E` 類別(如 `ValidationBoundaryE2E`)
   繼承 `ContainerIT` 也需要 Docker,但名稱不帶 IT 後綴,`-Dtest='!*IT'` 排不掉它們。
3. **`mvn -pl` 沒帶 `-am` 就是在測 m2 裡的舊 JAR**,跨模組改動務必加 `-am`。
4. failsafe 多類別參數用逗號不是 `+`。

⚠️ Phase 3 收尾狀態:

- 後端 commits(本 repo):`4b98759`、`2f15c33`、`e276de5`(Plan 01)。
- 前端 commits(`../../vue/stock-v2`):`4bdb229`、`157f7d8`(02)、`309598f`(03)、`40a4f2b`、`587e84e`(04)、
  `ef1fb35`、`c4abd7e`(05)。

- **兩個 repo 皆已 push 並 merge 進各自的 `develop`**(2026-07-26 以 `git branch -r --contains` 逐一查證:
  後端三個 commit 皆在 `origin/develop`;前端七個 commit 亦在 `origin/develop`,經 PR #8 合併,
  sibling repo 現處於 `develop @ a03e030`,工作樹乾淨)。舊記載「兩個 repo 皆未 push」已過時,故更正。
  → **Phase 4 的前端分支應從 sibling repo 的 `develop` 開,不要從 `feature/phase-03-portfolio-read` 開。**

- 三個頁面都已不再 import mock store(PORT-04 / judgment §3),API mode 一律經 `getRuntimeApiClients()`。
- 尚未驗證:前後端真實整合(Phase 5 範圍)。03-05-SUMMARY §(4) 有逐項契約對帳(16 PASS / 1 N/A / 0 FAIL),
  但那是「前端送出的 URL」對「03-01-SUMMARY 記錄的契約」的紙面比對,不是跑起後端打真實請求。
