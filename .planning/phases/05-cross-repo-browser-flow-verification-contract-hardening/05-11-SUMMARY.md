---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 11
subsystem: ui
tags: [vue, composable, useNow, fake-timers, vitest, field-errors]

requires:
  - phase: 05-09
    provides: "後端 POST /api/v1/trades 未來成交時間回 400 VALIDATION_FAILED + fields.executedAt = \"must not be in the future\""
  - phase: 05-10
    provides: "CashEntryDialog / OrderTicket 的 <style> 已改用 --danger(本 plan 只動 <script setup> 與測試)"
provides:
  - "OrderTicket:maxExecutedAt / executedAtIssue 改由 useNow 驅動(clockNow()),ticket 開著不動也會隨時間更新"
  - "CashEntryDialog:maxOccurredAt / occurredAtIssue 改由 useNow 驅動;送出前自檢 isOccurredAtValidNow() 仍用此刻 Date.now()"
  - "OrderTicket 元件測試:400 fields.executedAt=\"must not be in the future\" 綁到 ticket-field-error-executedAt(D-02a 前端面)"
affects: [OrderTicket, CashEntryDialog, frontend-component-tests]

actuals:
  tokens: 4200
  tasks: 2
  commits: 2
plan_head_before: 82dfc18fc610c0d66b67db613fb9bcc61b83e502
plan_head_after: 6a5986c06c1d4dff7adcc17fb670477ffe013862
commits: 2

tech-stack:
  added: []
  patterns:
    - "時間相依 computed 一律經 clockNow() = Math.max(useNow().value, Date.now()),useNow 以 active: () => props.open 啟停"
    - "送出當下的精確判斷直接讀 Date.now(),不依賴 30 秒粒度時鐘"
    - "假計時器測試用 vi.useFakeTimers({ now }) + vi.advanceTimersByTime,不用 vi.runAllTimers()"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.test.ts

key-decisions:
  - "OrderTicket 沒有獨立的送出前自檢;canSubmit 讀的 executedAtIssue 經 clockNow() 已含當下 Date.now(),不另加自檢"
  - "CashEntryDialog 送出前自檢改名 isOccurredAtValidNow(),以區域變數 nowMs = Date.now() 呼叫 classifyInstantInput(維持「此刻」語意,同時滿足 acceptance grep)"
  - "OrderTicket 模板註解改寫為後端三種 fields.executedAt(1900 下限 / 除權日時序 / 未來,後者為 Phase 5 D-02a 新增)"

patterns-established:
  - "OrderTicket / CashEntryDialog / HoldingEventDialog 三個事件時間對話框共用同一個 useNow + clockNow 模式"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "OrderTicket 成交時間 max 隨時間前進,未來提示在時間追上後消失(不需編輯欄位)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/OrderTicket.test.ts#OrderTicket 成交時間 — useNow 驅動上限與未來判斷(Phase 5 D-02d)"
        status: pass
    human_judgment: false
  - id: D2
    description: "後端 400 fields.executedAt=\"must not be in the future\" 顯示在 ticket-field-error-executedAt,value 與 message 不入 DOM"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/OrderTicket.test.ts#OrderTicket 成交時間 — 後端未來時間欄位錯誤綁定(Phase 5 D-02a / 05-09)"
        status: pass
    human_judgment: false
  - id: D3
    description: "CashEntryDialog 發生時間 max 隨時間前進,未來提示與 aria-invalid 自動消失、送出鈕恢復可按;既有送出案例維持綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/CashEntryDialog.test.ts#CashEntryDialog — useNow 驅動發生時間上限與未來判斷(Phase 5 D-02d)"
        status: pass
    human_judgment: false

duration: 約 10min
completed: 2026-11-09
status: complete
---

# Phase 5 Plan 11: OrderTicket / CashEntryDialog 未來時間判斷改用 useNow Summary

**兩個事件時間對話框的 datetime-local `max` 與「未來」判斷改由 `useNow({ active: () => props.open })` + `clockNow()` 驅動,開著不動也每 30 秒更新;另補元件測試證明 05-09 新增的 `fields.executedAt = "must not be in the future"` 綁到 OrderTicket 的欄位錯誤。**

## Performance

- **Duration:** 約 10 min
- **Completed:** 2026-11-09T15:03Z
- **Tasks:** 2
- **Files modified:** 4(全在前端 repo)

## Accomplishments

- `OrderTicket.vue`:新增 `now = useNow({ active: () => props.open })` 與 `clockNow()`(JSDoc 同 HoldingEventDialog 範本);`maxExecutedAt` 由 `ref('')` 改 `computed(() => toLocalInputValue(new Date(clockNow())))`;`executedAtIssue` 改 `classifyInstantInput(executedAt.value, clockNow())`;`resetTicket()` 只寫入 `executedAt` 預設值。模板註解更新為「後端三種 `fields.executedAt`」。
- `CashEntryDialog.vue`:同模式;`occurredAtIssue` 由普通函式改為 computed(`fieldErrorText` 改讀 `.value`),`occurredAtValid` 依它計算;送出前自檢改為 `isOccurredAtValidNow()`,以此刻 `Date.now()` 重算;移除不再使用的 `TimeFieldIssue` type import。
- 測試 +5(1319 → 1324):兩元件各一條假計時器行為測試 + 一條原始碼字面守門;OrderTicket 一條 400 `must not be in the future` 綁定測試(含 `aria-invalid`、`aria-describedby` 關聯、value / message 不入 DOM)。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`)

1. **Task 1(tracer):OrderTicket useNow + fields.executedAt 綁定測試**:`884a460` fix(05-11): OrderTicket 未來時間判斷改用 useNow,補 fields.executedAt 綁定測試
2. **Task 2:CashEntryDialog useNow**:`6a5986c` fix(05-11): CashEntryDialog 未來時間判斷改用 useNow

TDD:兩個 task 都先加測試確認紅燈 —— Task 1 為 2 failed(`前進 60 秒後上限應隨之前進: expected '2026-08-15T10:30' to be '2026-08-15T10:31'` 與原始碼守門);Task 2 為 2 failed(`expected '2026-10-24T09:30' to be '2026-10-24T09:31'` 與原始碼守門)。D-02a 綁定測試在改實作前即綠:`fieldErrorText('executedAt')` 本就對任何非 tooEarly 的 `fields.executedAt` 回 `tradeErrExecutedAt`,本 plan 補的是以 05-09 真實 fields 值鎖住該行為的證據。測試與實作依 plan 同 commit。

Tracer 回饋閘門:Task 1 commit 後重跑 `<verify>`(`npx vitest run src/components/OrderTicket.test.ts` → 85 passed)通過才進 Task 2。

## Verification

- `npx vitest run src/components/OrderTicket.test.ts` → 85 passed。
- `npx vitest run src/components/CashEntryDialog.test.ts src/components/OrderTicket.test.ts src/composables/useNow.test.ts` → 3 files / 141 passed。
- `npx vitest run src/pages/Positions.test.ts src/task4.test.ts`(同樣掛載這兩個元件)→ 91 passed。
- `npm run lint` → 0 errors(40 warnings 皆既有 `no-explicit-any`,與 05-10 基線相同,本 plan 未新增)。
- `npm run build`(vue-tsc + vite)→ built OK。
- `npm test` → 64 files / 1324 passed;`VITE_DATA_MODE=api npm test` → 64 files / 1324 passed。
- Acceptance:`useNow` 於 OrderTicket.vue 3 次、CashEntryDialog.vue 4 次(≥ 2);`classifyInstantInput(executedAt.value, Date.now())` = 0;`classifyInstantInput(occurredAt.value, Date.now())` = 0;`must not be in the future` 於 OrderTicket.test.ts 3 次(≥ 1)。
- 未使用 `vi.runAllTimers()`(僅出現在新增註解中提醒禁用)。

## Deviations from Plan

### 細節調整(非 Rule 1-4 修正)

**1. 假計時器測試步驟依「max = 現在、上限 + 10 分鐘」重寫**
- **Plan 原文:** 「`executedAt` 設為 T + 2 分鐘(未超過上限前的合法值),前進 30 秒兩次後 `:max` ≥ T + 1 分鐘」—— T + 2 分鐘在開啟當下其實是未來值,敘述自相矛盾。
- **實作:** 開啟於 T(10:30:00)→ 斷言 `max` = 10:30;前進 2 × 30 秒 → `max` = 10:31(輸入值不被改寫);輸入 `max + 10 分鐘` → 未來提示 + 前進鈕 disabled;再前進 11 分鐘 → 提示消失、`max` = 10:42、前進鈕可按。涵蓋 plan 的所有行為意圖,且 `max` 用等值斷言(比 ≥ 更嚴)。
- **Commit:** 884a460(OrderTicket)、6a5986c(CashEntryDialog 同步驟)

**2. 額外加原始碼字面守門**
- 兩元件各一條以布林斷言的 `?raw` 守門(`useNow({ active: () => props.open })` 存在、`Date.now()` 直讀不存在、`max*.value =` 寫入不存在),防日後回退;失敗時不傾印整份原始碼。

**Total deviations:** 0 個 auto-fix;2 個測試細節調整。**Impact:** 無範圍擴張,只動 plan 指定的 4 個檔案。

## Issues Encountered

None。

## Known Stubs

None。

## Threat Flags

None(T-05-20 accept:前端檢查只是 UX,後端 05-09 仍以 `fields.executedAt` / `fields.occurredAt` 拒絕;未新增任何網路端點或信任邊界)。

## Next Phase Readiness

- 無阻擋。前端 CI(Node 20)會再跑 lint → test → build;本 plan 的假計時器測試只用 microtask flush(`flushAsync` 在假計時器下不做 macrotask hop),與 LEARNINGS 2026-11-07 的既有做法一致。
- D-02 四條觀察中(a)前端面與(d)由本 plan 完成;(a)後端面為 05-09、(c)為 05-10。

## Self-Check: PASSED

- 2 個 commit(884a460、6a5986c)皆在 `claude/epic-curie-lj8alo` HEAD 歷史中;`git rev-list --count 82dfc18..HEAD` = 2。
- key-files 4 個皆存在且於上述 commit 中修改;STATE.md / ROADMAP.md 未修改。
