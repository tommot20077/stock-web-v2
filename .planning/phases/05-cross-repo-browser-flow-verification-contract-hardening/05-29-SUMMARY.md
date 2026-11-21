---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 29
subsystem: frontend
tags: [vue, apiClient, error-normalization, idempotency, i18n, vitest, G-05-7, FE-WR-01]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (D-26)
    provides: apiClient.transportFetch 把 fetch reject 正規化成 NETWORK_ERROR(status 0)
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (T-05-27)
    provides: 寫入 adapter 的嚴格 mapper(parseDecimal 遇非字串丟 TypeError)
provides:
  - "apiClient.ts apiRequestMapped<W, D>(path, map, options?):mapper 丟非 ApiClientError → ApiClientError{status: 實際回應 status, code: 'INVALID_API_RESPONSE', requestId: meta.traceId, message: 靜態字串};mapper 自丟 ApiClientError 原樣上拋"
  - "apiClient.ts 內部 requestEnvelope<T>(apiRequest 改為回傳其 data,對外行為不變)"
  - "4 個寫入 adapter 改用 apiRequestMapped:tradingApi.createTrade、cashApi.createEntry、holdingEventApi.createDividend、holdingEventApi.createShareAdjustment"
  - "i18n tradeErrInvalidResponse(zh 逐字 Yuan 文案 / en);文案硬規則 1、2 例外清單 = tradeErrNetwork、tradeErrInvalidResponse"
  - "OrderTicket SUBMIT_ERROR_COPY.INVALID_API_RESPONSE → tradeErrInvalidResponse(附 traceId、key 保留)"
  - "OrderTicket handleSubmitFailure 非 ApiClientError → UNKNOWN_ERROR;submitTrade 的 catch 只包 createTrade"
affects: [05-30, 05-31, G-05-7, FE-WR-01, VER-02]

actuals:
  tokens: 6758
  tasks: 3
  commits: 4
plan_head_before: a82fe746f4b4ac1321143694f1376871030b4a12
plan_head_after: 615a8687d2461880202b0841dd6b447b4fb67ecd
# plan_head_before / plan_head_after 是前端 repo(/home/user/stock-web-v2-front-end)的 commit;commits 依其量測。

tech-stack:
  added: []
  patterns:
    - "寫入 adapter 的 wire → domain mapper 一律經 apiRequestMapped 在 transport 邊界內執行;mapper 失敗只會是 INVALID_API_RESPONSE,不會以 TypeError 冒到元件"
    - "元件送出:內層 catch 只包 adapter 呼叫(失敗文案只用在請求本身失敗),外層 finally 涵蓋成功後步驟(送出中狀態撐到成功畫面渲染完)"
    - "測試「成功後例外不進錯誤區」:以 onErrorCaptured 宿主元件接住逃出 handler 的例外,並以 vi.mock passthrough + hoisted 開關注入失敗"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiClient.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiClient.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/tradingApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/tradingApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/cashApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/cashApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/i18n.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/i18n.test.ts

key-decisions:
  - "tradeErrInvalidResponse zh 文案逐字採 Yuan 2026-11-14 決定(ASCII 逗號,與 05-UAT / plan 一致;i18n.ts 既有較新文案如 tradeErrExecutedAt 也用 ASCII 逗號)"
  - "INVALID_API_RESPONSE 的 key 處置走預設保留(不加入丟棄清單):交易可能已寫入,同一把 key 重送命中後端冪等、拿回同一筆"
  - "apiRequestMapped 的 message 為靜態 'Response data did not match the contract',不夾帶 mapper 例外訊息或回應值(T-05-44)"
  - "submitTrade 改為外層 try/finally + 內層 catch 只包 createTrade(而非 plan 字面的「try 只包 createTrade、finally 照舊」),理由見 Deviations #1"
  - "讀取類 adapter(cashApi.listEntries 等)維持原樣,其 TypeError 案例(cashApi.test.ts listEntries)不動,屬 plan 範圍外"

patterns-established:
  - "寫入 adapter 新增時:用 apiRequestMapped(path, mapper, options),不要 mapper(await apiRequest(...))"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "apiRequestMapped:mapper 失敗 → INVALID_API_RESPONSE(真實 status + traceId + 靜態訊息);成功回傳 mapped 值;409 / 無信封 / 網路錯誤碼不變且 mapper 未被呼叫;mapper 自丟 ApiClientError 原樣上拋"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/apiClient.test.ts#apiRequestMapped(G-05-7 / FE-WR-01)"
        status: pass
    human_judgment: false
  - id: D2
    description: "4 個寫入 adapter 改用 apiRequestMapped;201 帶 JSON number 金額或缺欄位 → INVALID_API_RESPONSE(status 201、帶 traceId)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/tradingApi.test.ts#201 但 quantity 為 JSON number / 201 但缺 price"
        status: pass
      - kind: unit
        ref: "vue-app/src/services/cashApi.test.ts#201 但 amount 為 JSON number"
        status: pass
      - kind: unit
        ref: "vue-app/src/services/holdingEventApi.test.ts#httpCreateDividend201WithNumberAmount / httpCreateShareAdjustment201MissingQuantityDelta"
        status: pass
    human_judgment: false
  - id: D3
    description: "OrderTicket:201 + 壞 body → 顯示 tradeErrInvalidResponse(zh 逐字)+ traceId,不顯示網路文案,重送沿用同一把 Idempotency-Key"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/components/OrderTicket.test.ts#05-29 FE-WR-01:201 但 data 不合契約"
        status: pass
    human_judgment: false
  - id: D4
    description: "OrderTicket 非 ApiClientError → UNKNOWN_ERROR + tradeErrUnknown;createTrade 成功後的 UI 例外不進錯誤區;連點仍只記一筆"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/components/OrderTicket.test.ts#05-29 FE-WR-01:createTrade 以非 ApiClientError reject / 成功後的 UI 步驟丟錯"
        status: pass
      - kind: automated_ui
        ref: "vue-app/src/task4.test.ts#records only one trade when the submit button is clicked twice in a row"
        status: pass
    human_judgment: false
  - id: D5
    description: "i18n:tradeErrInvalidResponse zh / en 皆有,zh 逐字、不含「交易未記錄」,en 含 may have been recorded / Trades page;不在 TRADE_SUBMIT_ERROR_KEYS;受 forbidden term 測試涵蓋"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/i18n.test.ts#tells the user the trade may already be recorded when the response cannot be read (G-05-7)"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-11-14
status: complete
---

# Phase 5 Plan 29: apiRequestMapped 與 OrderTicket「回應無法解讀」文案 Summary

**寫入 adapter 的嚴格 mapper 改在 apiClient 邊界內執行(`apiRequestMapped`),201 之後的契約不符成為帶 status 與 traceId 的 `INVALID_API_RESPONSE`;OrderTicket 改提示「交易可能已記錄,請先到交易頁確認」並保留 idempotency key,非 ApiClientError 改標 `UNKNOWN_ERROR`。**

## Performance

- **Duration:** 約 15 min
- **Started:** 2026-11-14T16:36:07Z
- **Completed:** 2026-11-14T16:51:37Z
- **Tasks:** 3
- **Files modified:** 12(前端 repo)

## Accomplishments

- `apiClient.ts` 抽出內部 `requestEnvelope<T>`,新增並匯出 `apiRequestMapped<W, D>(path, map, options?)`。只有 mapper 包在 try/catch 裡:mapper 丟非 `ApiClientError` 時改丟 `INVALID_API_RESPONSE`(帶實際 status 與 `meta.traceId`,訊息是靜態字串);HTTP 失敗、信封不合、transport 失敗沿用原本的錯誤碼。
- 4 個寫入 adapter(`createTrade` / `createEntry` / `createDividend` / `createShareAdjustment`)改用 `apiRequestMapped`,`apiRequest` import 不再使用的就移除。`tradingApi.ts` 仍然沒有任何 transport 層字面。
- OrderTicket 新增 `INVALID_API_RESPONSE: 'tradeErrInvalidResponse'`,錯誤區顯示 traceId,key 保留,重送帶同一把 `Idempotency-Key`。
- OrderTicket 的非 `ApiClientError` 分支改為 `UNKNOWN_ERROR`(OrderTicket.vue 已沒有 `code: 'NETWORK_ERROR'`)。catch 只包 `createTrade`,所以成功之後的 UI 例外會走 Vue 的錯誤處理,不會被說成「交易未記錄」。
- i18n 新增 `tradeErrInvalidResponse` 的 zh / en 文案;zh / en 文案硬規則註解與 `i18n.test.ts` 同步更新(例外清單 = `tradeErrNetwork`、`tradeErrInvalidResponse`)。

## Task Commits

全部在前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`:

1. **Task 1(tracer):201 + 壞 body → apiRequestMapped → createTrade → OrderTicket 文案 + traceId + key 保留** - `3fa32c3`(fix)
2. **Task 2:apiRequestMapped / createTrade 單元案例 + i18n 規則測試 + OrderTicket UNKNOWN_ERROR / 縮小 try** - `434fc51`(fix)
3. **Deviation fix:OrderTicket 送出中狀態撐到成功畫面渲染完(修 434fc51 造成的連點回歸)** - `cc5029c`(fix)
4. **Task 3:cashApi.createEntry 與 holdingEventApi 兩個寫入改用 apiRequestMapped** - `615a868`(fix)

**Plan metadata:** 本 SUMMARY 於後端 repo 以 pathspec commit(docs(05-29))

## Files Created/Modified

- `vue-app/src/services/apiClient.ts`:`requestEnvelope`(內部)、`apiRequestMapped`(匯出,附繁中 JSDoc)
- `vue-app/src/services/apiClient.test.ts`:`apiRequestMapped` 六條路徑
- `vue-app/src/services/tradingApi.ts`:`createTrade` 改用 `apiRequestMapped(toTradeRecord)`
- `vue-app/src/services/tradingApi.test.ts`:201 number quantity、201 缺 price;既有 number 金額案例改成斷言 `INVALID_API_RESPONSE`
- `vue-app/src/services/cashApi.ts` / `cashApi.test.ts`:`createEntry` 改用;201 number amount 案例
- `vue-app/src/services/holdingEventApi.ts` / `holdingEventApi.test.ts`:兩個寫入改用;201 number amount、201 缺 quantityDelta 案例;既有 2 個 TypeError 案例改成斷言 `INVALID_API_RESPONSE`
- `vue-app/src/components/OrderTicket.vue`:文案分派、JSDoc、`UNKNOWN_ERROR`、submitTrade 結構
- `vue-app/src/components/OrderTicket.test.ts`:3 個 05-29 案例;`mountApiTicket` / `mountSubmitTicket` 加選用 `lang` 參數;`portfolioRevision` passthrough mock(預設透傳)
- `vue-app/src/i18n.ts` / `i18n.test.ts`:新 key、硬規則註解、專屬規則測試、`PHASE4_TRADE_KEYS` 加入新 key

## Decisions Made

見 frontmatter `key-decisions`。重點:zh 文案逐字照 Yuan 的決定(ASCII 逗號);`INVALID_API_RESPONSE` 保留 key;mapper 失敗訊息用靜態字串。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] 縮小 try 範圍後,送出中狀態太早解除,連點會記兩筆**
- **Found during:** Task 3 的全量閘門(`npm test`)
- **Issue:** Task 2 照 plan 字面把 try/finally 縮到只包 `createTrade`。結果 `submitting` 在 `createTrade` resolve 之後、`step = 'result'` 渲染之前就變回 false。DOM 更新前落在舊送出鈕上的連點會通過 `if (submitting.value) return` 守衛,而這時 key 已清空,於是用新 key 再記一筆。`src/task4.test.ts` 的「records only one trade when the submit button is clicked twice in a row」因此變紅(預期 8 筆,實得 9 筆)。
- **Fix:** 改成外層 `try { … } finally { submitting.value = false }` 涵蓋成功後的步驟(時序與原本相同),內層 `try/catch` 只包 `createTrade`(catch → `handleSubmitFailure` 後 return)。成功之後的例外仍然不會進 `handleSubmitFailure`,plan 的意圖保留下來。
- **Files modified:** `vue-app/src/components/OrderTicket.vue`
- **Verification:** `task4.test.ts` 與 `OrderTicket.test.ts` 共 123 個案例全綠,包含「成功後的 UI 步驟丟錯」案例;全量 1575 個案例兩種 mode 都綠
- **Committed in:** `cc5029c`(獨立 fix commit)

**2. [Rule 3 - Blocking] 既有測試斷言 TypeError,依新契約改寫**
- **Found during:** Task 1 / Task 3
- **Issue:** `tradingApi.test.ts`「回應金額仍為 JS number 時在邊界丟錯」和 `holdingEventApi.test.ts` 的兩個 http number 案例原本斷言 `TypeError`。新行為按設計會改丟 `INVALID_API_RESPONSE`。
- **Fix:** 改成斷言 `ApiClientError{code: 'INVALID_API_RESPONSE', status: 200, requestId}`。T-05-27 的「在邊界大聲失敗」仍然成立;純 mapper 單元(`toDividend` 丟 TypeError)和讀取類 adapter(`cashApi.listEntries`)的 TypeError 案例不動。
- **Committed in:** `3fa32c3`、`615a868`

---

**Total deviations:** 2 auto-fixed(1 Rule 1 bug、1 Rule 3 blocking)
**Impact on plan:** 都是為了正確性必須改的;沒有擴大範圍。

## Issues Encountered

- Task 2 的 `apiClient.test.ts`「200 無信封」案例,fixture 一開始帶了 `data` 欄位,而 `isApiSuccess` 只檢查有沒有 `data`,所以測試不成立。fixture 改成不帶 `data` 的 body 之後就綠了(測試本身的錯,不是產品問題)。

## Verification(前端閘門)

| 命令 | 結果 |
|---|---|
| `npm run lint` | exit 0;0 errors / 38 warnings(都是既有的 `no-explicit-any`,本 plan 動到的檔案沒有新增 warning) |
| `npm test` | 67 files / 1575 tests 全過 |
| `VITE_DATA_MODE=api npm test` | 67 files / 1575 tests 全過 |
| `npm run build`(含 vue-tsc) | exit 0 |
| Playwright layout.spec | 未跑:本 plan 沒有改版面 / CSS |
| 真後端瀏覽器 E2E | 延到 CI(本機沒有 Docker) |

Acceptance criteria 全部重新跑過且通過:T1(1 / 3 / 2 / 1 / 1)、T2(NETWORK_ERROR 字面 0、apiClient.test 8、i18n.test 7)、T3(cashApi 3、holdingEventApi 4、測試 INVALID_API_RESPONSE 合計 9)。

## Known Stubs

None.

## User Setup Required

None - 不需外部服務設定。

## Next Phase Readiness

- 05-30 / 05-31 可以直接沿用 `apiRequestMapped`、OrderTicket 的 `UNKNOWN_ERROR` 修正,以及 `tradeErrInvalidResponse`。其餘 11 處非 ApiClientError 分支、App.vue `messageFromStartupError`、「NETWORK_ERROR 只在 apiClient.ts 產生」的源碼守門,都交給 05-30 / 05-31。
- 對話框層(CashEntryDialog / HoldingEventDialog)遇到 `INVALID_API_RESPONSE` 時的文案行為(cashErrUnknown / holdingEventErrUnknown 顯示 traceId),依 plan 由 05-30 驗證。

## Self-Check: PASSED

- 12 個 modified 檔案都存在
- 前端 commit 3fa32c3、434fc51、cc5029c、615a868 都在 HEAD 祖先鏈上(`git rev-list --count a82fe74..HEAD` = 4)

---
*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Completed: 2026-11-14*
