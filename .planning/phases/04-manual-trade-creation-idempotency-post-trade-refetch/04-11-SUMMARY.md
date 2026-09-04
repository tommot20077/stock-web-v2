---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 11
subsystem: frontend
tags: [order-ticket, idempotency, error-dispatch, a11y, sell-precheck, vue, vitest]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-07 tradingApi(createTrade + Idempotency-Key)與 portfolioRevision、04-08 的 15 條錯誤文案、04-09 三步驟骨架、04-10 typeahead 與報價卡
provides:
  - "D-14 idempotency key 的三條生命週期規則(送出時產生 / 重試沿用 / 改欄位換新)"
  - "U-04 的 key 處置表:KEY_REUSED 丟棄、其餘一律保留"
  - "D-16 錯誤分派:fields 的 key 綁欄位、其餘依 error.code 分派到底部單一區域"
  - "D-15 SELL 預檢(可賣數量三態 + 超量阻擋),只讀 symbol 與 totalQuantity"
  - "D-09 只渲染後端 TradeDto 的成功畫面"
affects: [04-12 三頁 post-trade refetch, 04-13 雙 mode 收尾, Phase 5 真實後端驗證]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "key 生命週期的斷言直接讀 Idempotency-Key header,而不是 stub adapter —— 證明 key 真的抵達傳輸層"
    - "錯誤分派用 code → i18n key 的對照表,絕不依賴錯誤出現順序"
    - "「不得出現在 DOM」這類 negative 契約,值得用一句刻意好認的 fixture 字串(BackendMessageMustNotReachTheDom)"
    - "註解不得寫出被 ?raw 檢查禁止的字面 —— 本 plan 第三次踩到同一個坑(04-07 已列入 patterns)"

key-files:
  created: []
  modified:
    - "[FE] src/components/OrderTicket.vue"
    - "[FE] src/components/OrderTicket.test.ts"

key-decisions:
  - "401 在 handleSubmitFailure 直接 return(完全不設 submitError):ticket 內連診斷列都不出現,語意完全交給全域 SessionBanner"
  - "NETWORK_ERROR 是前端合成的診斷碼:讓「連不上伺服器」與「後端回了未知 code」在分派表上可明確區分,而後者的文案不敢承諾「重試不會建立重複」"
  - "holdings 快取只在 portfolioRevision 變動時失效,resetTicket 不動它 —— 開一張 ticket 不會改變持倉,重開就丟快取是無謂的請求"
  - "送出中的凍結用原始碼斷言補足 DOM 斷言:review 步驟時 step-1 的輸入根本不在 DOM(v-if),行為測試抓不到"

patterns-established:
  - "跨步驟的狀態凍結:DOM 測不到的部分用 ?raw 鎖綁定,並在測試裡寫明為什麼 DOM 測不到"

requirements-completed: [TRAD-02, TRAD-04, TRAD-06]

# Metrics
duration: 約 55min
completed: 2026-08-16
---

# Phase 04 Plan 11: 送出路徑、key 生命週期、錯誤分派與 SELL 預檢

**把 order ticket 的送出路徑補完:連點只送一次、idempotency key 有可測的生命週期、11 種後端錯誤各有正確落點與文案、SELL 顯示可賣數量但不重算損益、成功畫面一格都不用表單值 —— 29 條新測試鎖住。**

## Performance

- **Duration:** 約 55 min
- **Tasks:** 2/2(各一組 RED → GREEN)+ 1 條自查後補的偏離修正
- **Files modified:** 2(皆在前端 repo,即 plan 的 `files_modified` 全集)

## Accomplishments

- **D-14 與 D-07 的互鎖真的走得通。** Test 28 完整跑了「400 `VALIDATION_FAILED` → 改數量 → 再送 → 200」這條路徑,並斷言第二次的 key 與第一次**不同**。這是 CONTEXT.md 明文要求、Pitfall 11 的驗收 —— 若採「一張 ticket 一把 key」,使用者在這裡會吃 409 而卡死。
- **key 的斷言讀的是 HTTP header,不是函式參數。** 測試 stub 的是 `fetch`,`idempotencyKeys()` 直接從 `RequestInit.headers` 取 `Idempotency-Key`。04-07 的 `tradingApi.test.ts` 已鎖住 adapter 自己的行為;本 plan 鎖的是「元件 → header」整條路徑,兩者不重複。
- **U-04 的無出路迴圈被實際堵住。** Test 42 分兩段:`TRADE_IDEMPOTENCY_KEY_REUSED` 之後下一次送出**必須換新 key**;`TRADE_CONFLICT` 之後**必須沿用**。兩個方向都有斷言,單向實作會被抓到。
- **後端的英文訊息在 DOM 零出現。** fixture 的 `error.message` 一律是 `BackendMessageMustNotReachTheDom`,`fields` 的 value 是真實的 Bean Validation 句子 `must be greater than or equal to 0.00000001`。Test 36 對兩者各有一條 `not.toContain`,Test 39 的 11 個 code 也逐一檢查 message 未外洩。
- **U-08 被機械化驗證,而不只是寫在文件上。** Test 39 對每一個 code(含 `AUTH_CSRF_TOKEN_INVALID`)都斷言 `onRefreshFailed` **沒有**被呼叫 —— CSRF 403 不得升級成全域 session banner。Test 43 則反向確認 401 **會**呼叫它且 ticket 內零錯誤節點。
- **judgment §5 有實質驗收。** Test 48 讓前端預檢通過(可賣 100、只賣 10)後,後端仍回 409 `TRADE_INSUFFICIENT_HOLDING`,並斷言底部正確顯示。前端 guard 不是防護,這條測試就是那句話的可執行版本。
- **成功畫面用「刻意矛盾」的 fixture 鎖住。** 表單送 10 @ 190.20,後端回 7 @ 188.88;Test 30 斷言畫面顯示 **7** 與 **188.88**,且 `ticket-result-price` **不含** `190.20`。任何一格改用表單值都會立刻紅。

## Task Commits

前端 repo `D:\end\workspace\vue\stock-v2`,分支 `feature/phase-04-manual-trade-creation`:

1. **Task 1:重複送出阻擋 + D-14 key 生命週期 + D-09 成功畫面**
   - `11475cc` (test) — RED:13 條(Test 22~34)
   - `0f411c0` (feat) — GREEN
2. **Task 2:D-16 錯誤分派 + D-15 SELL 預檢**
   - `49ada21` (test) — RED:16 條(Test 35~50)
   - `4556422` (feat) — GREEN
3. **偏離修正(自查後補)**
   - `e8d1f35` (fix) — 送出鈕 `min-width`(見 Deviations)

本 SUMMARY 提交在後端 repo `feature/phase-04-trade-idempotency` 分支。**兩個 repo 都未 push,也未開 PR。**

## Files Modified

- `[FE] src/components/OrderTicket.vue` — `currentKey` / `dirtySinceSubmit` / `submitError` 三個狀態、`SUBMIT_ERROR_COPY` 與 `FIELD_ERROR_COPY` 兩張對照表、`handleSubmitFailure`、holdings 快取與 `sellableQty` / `oversellError`、七個欄位的 `aria-invalid` + `aria-describedby` + 錯誤節點、底部錯誤區與其診斷列。
- `[FE] src/components/OrderTicket.test.ts` — 34 條(04-09/04-10)→ **63 條**。新增 29 條全部為本 plan。

## plan output 要求記錄的五件事

### 1. U-08 是**刻意覆寫** `02-UI-SPEC.md:144`,不是「不衝突」

`02-UI-SPEC.md:144` 把 `AUTH_CSRF_TOKEN_INVALID` **無條件**列入全域 banner 清單。Phase 4 依 D-16 **刻意收窄**它:

> **app 啟動時的 CSRF bootstrap 失敗 → 全域 banner;單一 unsafe 請求被 CSRF 拒絕 → 該請求的發起處(ticket 底部)。**

D-16 是更晚且更具體的決策,以它為準。**`apiClient` 一個位元組都沒改** —— 這個邊界本來就由既有 code 支撐:

| 既有證據 | 本 plan 的關係 |
|---------|--------------|
| `apiClient.ts:212` bootstrap 失敗丟 `AUTH_CSRF_TOKEN_MISSING`(`status: 0`) | 那才是全域性失敗(所有 unsafe 請求都不可能成功) |
| `apiClient.ts:315` / `:323` 是全檔唯二呼叫 `onRefreshFailed` 的位置,兩處都在 401 路徑 | 與 CSRF 403 無關,所以 CSRF 403 天生就不會升級 |
| `apiClient.test.ts:395` 已鎖「CSRF 403 只 reject 成 typed `ApiClientError`」 | 本 plan 只決定 ticket 這個呼叫端怎麼呈現自己拿到的 typed error |

本 plan 新增的是**呼叫端的驗收**:Test 39 對 `AUTH_CSRF_TOKEN_INVALID` 斷言 `ticket-error` 顯示 `tradeErrCsrf` **且** `onRefreshFailed` 未被呼叫。實作上 `AUTH_CSRF_TOKEN_INVALID` 與 `AUTH_CSRF_TOKEN_MISSING` 都映到 `tradeErrCsrf`(文案指示「重新整理頁面」)。

### 2. U-04 key 處置表的實作結果

實作落在 `handleSubmitFailure`,只有一行例外:

```ts
if (described.status === 401) return;              // 不顯示、不動 key
submitError.value = described;
if (described.code === 'TRADE_IDEMPOTENCY_KEY_REUSED') currentKey.value = null;
```

| code | 處置 | 為什麼 |
|------|------|--------|
| `TRADE_IDEMPOTENCY_KEY_REUSED` | **丟棄** | 文案要使用者「確認欄位後重新送出」。若沿用同一把 key,他會再吃一次 409 —— 而且他不會知道要**關掉整張 ticket** 才能繼續。這是唯一會讓使用者卡在無出路迴圈的情境,所以是唯一的例外 |
| `TRADE_CONFLICT` | 保留 | 「這次沒寫入,但意圖沒變」。沿用同一把重送是安全的,這正是文案敢寫「不會建立重複交易」的前提 |
| 網路失敗 / 5xx | 保留 | 結果未知,重送必須靠同一把 key 才不會建出第二筆 |
| `VALIDATION_FAILED` / `fields` 類 / `TRADE_INSUFFICIENT_HOLDING` | 保留 | 使用者一改欄位,`dirtySinceSubmit` 就會自動丟棄 |
| CSRF / 403 | 保留 | 重新整理或換帳號後意圖不變 |
| 401 | 保留 | 登入回來後重送的是同一個意圖 |

**額外實作決定:401 完全不設 `submitError`。** 不是「顯示一個比較低調的錯誤」,而是 ticket 內連診斷列都不出現(Test 43 斷言 `ticket-error` 為 null)。理由是 session 問題的正確處置在全域 banner,ticket 裡再放一條只會讓使用者以為有兩個問題。

### 3. U-03:冪等命中**不做**「這筆已存在」的變體

**理由(未改變,原樣執行 UI-SPEC §6):目前的 API 契約沒有任何 replay 訊號** —— 後端回傳的就是既有 `TradeDto`,與首次建立的回應逐欄相同。要區分就得新增 response header 或信封欄位,那是 **API 契約 shape 變更**,judgment §9 要求先問 Yuan,executor 不得自行加。

實作上冪等命中與首次建立走**完全同一條路徑**(同一個 `try` 區塊,沒有任何分支)。連帶契約也守住了:**replay 一樣呼叫 `notifyTradeCreated(trade)`**。Test 33 用「網路失敗 → 重試成功」這條真實路徑驗證:第一次失敗時 `portfolioRevision.value` 仍為 0,第二次(同一把 key)成功後三個訊號同時就位。少做這件事,會讓「網路失敗後重試成功」的使用者看不到 portfolio 更新。

### 4. D-15 只讀 `symbol` 與 `totalQuantity` 的自我約束

`sellableQty` 的實作只碰兩個欄位:

```ts
const match = state.data.find(item => item.symbol === selected.value!.symbol);
return match ? match.totalQuantity : 0;
```

`HoldingDto` 其餘的成本、市值、已實現/未實現損益與報酬率欄位一律未使用 —— 用它們算「賣出後的損益預估」會踩 Phase 3 D-04 與 judgment §7。

**Test 50 用 `?raw` 對整個 `OrderTicket.vue` 斷言那五個欄位名的字面命中為 0**,而不只是「SELL 預檢區塊內」。整檔範圍更嚴格,而且該檔本來就沒有引用它們(執行前已 grep 確認 0 命中),所以收緊沒有代價。測試的另一半正向斷言 `totalQuantity` 與 `listHoldings` 有出現 —— 避免「把功能刪掉也能過」的假綠。

fixture 也在幫忙:`holding()` 把那七個欄位全部給 `-999`,任何前端重算都會產生一眼可辨的荒謬數字。

**零持倉的文案硬規則也守住了。** 後端 SQL 有 `total_quantity > 0` 過濾(`JdbcTradingRepository.java:210`),「從未持有」與「已全數賣出」在回應裡不可分,所以只能說 `可賣數量:0` / `Sellable qty: 0`,不能說「您未持有此標的」。Test 46 對 `未持有` / `do not hold` / `not hold` / `No holdings` 四種說法各有一條 `not.toContain`。

### 5. 人工檢視項目的實際結果(誠實揭露:**我沒有開過瀏覽器**)

plan 的 `<verification>` 列了三項人工檢視。**本次執行環境沒有瀏覽器,這三項我做的是程式碼層檢視,不是視覺確認。** 逐項如下:

| 項目 | 我實際做了什麼 | 結論 |
|------|--------------|------|
| 送出中的視覺凍結是否明顯 | 逐條核對凍結清單的 CSS 效果:`.btn-accent:disabled` / `.btn-ghost:disabled` 是 `opacity: .4`、`.inp:disabled` 是 `opacity: .5`,加上 `aria-busy` 與常駐的 `記錄中…` 狀態列 | 程式碼層**齊備**;「是否夠明顯」屬視覺判斷,**未經人眼確認** |
| 320px 下 code 與 traceId 換行不水平滾動 | `.submit-error .details` 用 `flex-wrap: wrap` + `gap: 4px 8px`,`span` 為 `overflow-wrap: anywhere` —— 形狀逐字沿用 Phase 3 已驗收的 `Positions.vue:857-861` | 沿用既有已驗收形狀,風險低,但**未實測 320px** |
| `記錄中…` / `Recording…` 的按鈕寬度是否造成版位跳動 | **實際發現缺口並修正**:送出鈕原本沒有 `min-width`,標籤切換一定會縮放。已補 `.btn-submit { min-width: 136px }`(見 Deviations) | **缺口已修**;136px 是依字寬估算,**未在瀏覽器量測** |

上述三項的真正驗收屬 **Phase 5 / VER-03**(需真實後端與瀏覽器)。本 SUMMARY 不宣稱已完成視覺驗證。

## Decisions Made

### 1. key 的斷言讀 header,不 stub adapter

plan 的 behavior 寫「用未 resolve 的 promise stub `createTrade`」。實作改為 **stub `fetch`**,理由:

- 04-07 的 `tradingApi.test.ts` 已經把 adapter 自己的行為(逐欄投影、必帶 header、401 replay 沿用同一把 key)鎖死了。再 stub 一次 adapter,測的是同一件事。
- 本 plan 真正要證明的是**元件產生的 key 有沒有抵達傳輸層**。讀 `RequestInit.headers` 的 `Idempotency-Key` 是這件事唯一的直接證據。
- 順帶讓 Test 34 的 payload 契約斷言(七欄 sorted key `toEqual`)在元件層也成立,而不只在 adapter 層。

代價是測試要處理 CSRF cookie(`document.cookie = 'XSRF-TOKEN=...'`,沿用 `tradingApi.test.ts:100-102` 的手法),那是既有慣例,不是新機制。

### 2. `NETWORK_ERROR` 是前端合成的診斷碼

UI-SPEC 的分派表把「非 `ApiClientError`」與「其他(未知 code)」分成兩條不同文案,而前者是**唯一**敢承諾「重試不會建立重複交易」的那一條。若兩者共用同一個 fallback,使用者在後端回一個新 code 時會收到一句不成立的承諾。

實作用 `code: 'NETWORK_ERROR'` 標記非 `ApiClientError` 的情形(後端不會回這個值,不可能撞號),分派表對它有一筆,其餘未命中者才落到 `tradeErrUnknown`。診斷列因此在斷線時顯示 `NETWORK_ERROR`,traceId 那一格則 `v-if` 不渲染(Test 40 斷言 `ticket-error-trace-id` 為 null 且畫面不含 `null`)。

### 3. holdings 快取只跟 `portfolioRevision` 走,`resetTicket` 不碰它

「該 ticket 生命週期內快取」的字面做法是每次開 ticket 就丟掉。實作選了更嚴格的判準:**只有交易成功才會改變持倉**,所以快取失效綁 `portfolioRevision`,重開 ticket 不重讀。

這讓 Test 49 變成真的在測那個 watch —— 若改成「開 ticket 就重置」,同一條測試在沒有 watch 的實作下也會綠(`recordAnother` 會 reset),那是假綠。

### 4. 送出中的凍結:DOM 測不到的部分用 `?raw` 補,並在測試裡寫明原因

UI-SPEC §5 要求「所有表單輸入在 `submitting` 時 disabled(即使當前在 review 步驟不可見)」。但 step 1 是 `v-if`,送出中(停在 review)那五個輸入**根本不在 DOM**,`querySelector` 拿不到,行為斷言無從下手。

Test 24 因此分兩段:DOM 可觀察的部分(back-to-edit disabled、遮罩與 ✕ 點了不關、`aria-busy`、`role="status"` 的可讀文字)照常用行為斷言;五個輸入的 `:disabled="submitting"` 用 `sourceTagOf()` 從 `?raw` 切出該標籤來斷言。**測試裡直接寫明為什麼不用 DOM**,免得後人以為是偷懶。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] 送出鈕缺少 `min-width`,標籤切換會造成版位跳動**

- **Found during:** Task 2 完成後的 acceptance criteria 自查
- **Issue:** plan 的 action A 與 UI-SPEC §5 都明文要求「`min-width` 固定使標籤變化不造成版位跳動」,但 Task 1 只做了 `:disabled` 與標籤切換,`.btn-accent` 沒有任何寬度下限。`Record trade`(約 88px)切成 `Recording…`(約 70px)會讓按鈕左緣在送出瞬間往右跳。
- **Fix:** 送出鈕加 `btn-submit` class,`.btn-submit { min-width: 136px }`(136 = 最寬標籤 88px + `.btn-accent` 的 48px 水平內距,且為 4 的倍數)。CSS 註解算了 320px 的容納空間:footer 可用 272px,ghost 鈕約 118px + 12px gap + 136px = 266px,不換行。
- **TDD:** 先在 Test 22 加兩條斷言(送出鈕帶 `btn-submit`、`.btn-submit` 宣告 `min-width`),**實測轉紅**(`expected '<button…' to contain 'btn-submit'`)後才改 CSS。jsdom 不套用 scoped CSS 也算不出寬度,所以用原始碼斷言而非 `getComputedStyle`。
- **Committed in:** `e8d1f35`

**2. [Rule 3 - 驗收條件字面衝突] 註解寫出了 `?raw` 檢查禁止的欄位名**

- **Found during:** Task 2 GREEN 第一次執行
- **Issue:** Test 50 斷言整檔不含 `avgCost` 等五個字面,但我在說明「**不得**使用這些欄位」時把它們全列了出來,測試立刻紅(`expected '<template>…' not to contain 'avgCost'`)。
- **Fix:** 註解改寫為「其餘的成本、市值、已實現/未實現損益與報酬率欄位」,保留完整取捨理由但不出現被禁字面,並補一句說明「本段刻意不寫出字面名稱,好讓『沒有引用』可被機械驗證」。
- **Verification:** 63/63 綠。
- **Committed in:** `4556422`
- **註:** 這是 **04-07 Deviation #2 的同型重演**(該 SUMMARY 已把「註解會被機械檢查與編譯器一併讀到」列入 `patterns-established`)。我在寫註解時仍然踩了 —— 這條 pattern 顯然還需要更主動的檢查習慣,不只是寫在文件上。

---

**Total deviations:** 2 auto-fixed(1 × Rule 2 漏做 plan 明文要求 / 1 × Rule 3 字面衝突)
**Impact on plan:** 無範圍蔓延。`git log --name-only` 確認五個 commit 只動了 2 個檔案,皆為 plan 的 `files_modified` 所列;未觸碰任何後端原始碼、未動 `apiClient.ts`、未動 `i18n.ts`(所有文案皆複用 04-08 既有 key)。

## TDD 誠實記錄(CLAUDE.md 硬約束)

**我親眼看到的 RED:**

| 階段 | RED 指令輸出 | 紅 / 綠 |
|------|-------------|--------|
| Task 1 | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` → `7 failed \| 40 passed (47)` | **7 紅** / 6 綠(共 13 條新測試) |
| Task 2 | 同上 → `13 failed \| 50 passed (63)` | **13 紅** / 3 綠(共 16 條新測試) |
| min-width 修正 | 同上 `-t "Test 22"` → `1 failed \| 62 skipped` | **1 紅**(擴充既有測試) |

**一寫就綠的測試,逐條說明(不假稱經歷過 RED):**

| 測試 | 為什麼一寫就綠 |
|------|--------------|
| Test 22(送出中 disabled + 標籤) | 04-09 骨架已有 `:disabled="submitting"` 與標籤切換。本 plan 只是把它寫成測試 —— **但同一條測試的 `min-width` 那半段是真 RED**(見上表第三列) |
| Test 23(連點只送一次) | 04-09 的 `if (submitting.value) return;` 已存在。防迴歸性質 |
| Test 24(凍結清單) | 04-09 已做齊 `aria-busy` / `role="status"` / 六個輸入的 `:disabled` / `onClose` 與 `onMaskClick` 的 submitting 守衛。本 plan 是把散落的六列**寫成一條可執行的清單** |
| Test 25(key 產生時機) | 04-09 的 `newIdempotencyKey()` 本來就在送出時才呼叫。**但它沒有生命週期** —— Test 26/27/29 立刻紅,證明這條只鎖住了三分之一 |
| Test 31(trade-id / executedAt / 無撮合語意) | 04-09 已從 `recorded` 渲染。防迴歸性質 |
| Test 34(payload 七欄) | 04-07 的逐欄投影 + 04-09 的 `toLocalIso` 已就位。價值在於**元件層**也鎖住了(先前只有 adapter 層有) |
| Test 36(fields value 不入 DOM) | Task 2 RED 時**還沒有任何欄位級渲染**,所以必然綠 —— 這是典型的假綠。它的真實價值在 Task 2 GREEN **之後**:實作若改成 `{{ submitError.fields.quantity }}` 就會紅。我保留它並在此誠實標示 |
| Test 40(network 文案) | Task 1 GREEN 已把 `NETWORK_ERROR` 映到 `tradeErrNetwork`(為了讓 Task 1 的中間狀態不至於「失敗卻無任何提示」) |
| Test 41(成功態無 traceId) | 成功時 `submitError` 為 null,診斷列本來就不渲染。純防迴歸 |

**沒有任何「先寫實作再補測試」的情況;每個 task 的 `test(...)` commit 都在 `feat(...)` 之前**(`git log --oneline` 可核對)。

## 驗收指令與結果(全部為我實際執行的輸出)

| 指令 | 結果 |
|------|------|
| `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | **63/63 綠**(04-09 的 13 + 04-10 的 21 + 本 plan 的 29) |
| `npm test`(mock mode) | 35 files / **338 tests** 全綠(基準線 309 → +29) |
| `VITE_DATA_MODE=api npm test` | 35 files / **338 tests** 全綠 |
| `npm run build`(`vue-tsc --noEmit && vite build`) | exit 0 |
| `git status --porcelain`(前端 repo) | 空(工作樹乾淨) |

### acceptance criteria 逐條對帳(`grep -c` 實測)

**Task 1**

| 條件 | 實測 |
|------|------|
| 含 `crypto.randomUUID` | 2 ✅ |
| **不含** `Math.random` | 0 ✅ |
| 含 `:disabled="submitting` | 10 ✅ |
| 含 `aria-busy`(1)、`role="status"`(1) | ✅ |
| 含 `dirtySinceSubmit`(6)、`notifyTradeCreated`(3) | ✅ |
| 含 `ticket-submitting-status` / `ticket-result-trade-id` / `ticket-result-price` / `ticket-result-executed-at` | 各 1 ✅ |
| result 區塊**不含** `avgFillPx` / `orderId` / `estFee` | 各 0 ✅ |
| 含 `toLocalIso`(重用既有 helper) | 2 ✅ |
| 聚焦測試 exit 0 | ✅ |

**Task 2**

| 條件 | 實測 |
|------|------|
| 含 `ticket-error` / `ticket-error-code` / `ticket-error-trace-id` | 各 1 ✅ |
| 含 `ticket-field-error-` | 7(七個欄位各一)✅ |
| 含 `ticket-sellable-qty` / `-loading` / `-failed` | 各 1 ✅ |
| 含 `aria-invalid`(6)、`aria-describedby`(7)、`role="alert"`(2 個屬性 + 2 處註解提及) | ✅ |
| **不含**直接輸出 `fields` value 的表達式(`grep -nE "fields\.[a-zA-Z]+ *\}\}"`) | rc=1,零命中 ✅ |
| SELL 預檢**不含** `avgCost` / `costBasis` / `unrealizedPnl` / `realizedPnl` / `roi` | **整檔**各 0 ✅ |
| 含 `portfolio.listHoldings` | 1 ✅ |
| **不含** `error.message` | 0 ✅ |
| `npm test` / API mode / `npm run build` | 全部 exit 0 ✅ |

### 自我驗證 grep(§Typography / §Spacing)

**字級**(`grep -nE 'font-(size|weight)'` 濾掉 12/13/16/20px 與 400/600):**零表外違規**。

**間距**(`grep -nE '(padding|margin|gap)[^:]*:[^;]*[0-9]+px'` 濾掉 4 的倍數):唯一命中仍是 `.step-dots { gap: 6px }`,屬 §Spacing Exceptions 第 2 類(裝飾性 step dot)。本 plan 新增的 `.field-error`(`margin: 8px 0 0`)、`.submit-error`(`margin: 0 24px 16px`、`gap: 4px 8px`、`margin-top: 4px`)、`.btn-submit`(`min-width: 136px`)全部為 4 的倍數。

## Known Stubs

**無。** 送出路徑、錯誤分派、SELL 預檢皆為完整實作,無 TODO、無 FIXME、無佔位、無硬編碼空值。

**一個刻意的設計邊界(不是待補實作):** `executedAt` 的欄位級錯誤節點(`ticket-field-error-executedAt`)已就位,但**後端不會**把它放進 `fields` —— `CreateTradeRequest` 的 `executedAt` 沒有任何 Bean Validation 註解。它承接的是前端自檢之外的意外情況;正常路徑走 `tradeErrExecutedAt` 的表單層提示。

## Threat Model 對帳

| Threat ID | Disposition | 落實情形 |
|-----------|-------------|----------|
| T-04-10 | mitigate | key 用 `crypto.randomUUID()`(Web Crypto CSPRNG);`Math.random` 字面零命中 |
| T-04-01 | mitigate | D-14 兩條規則 + U-04 處置表,Test 26/27/29/42 四條測試從不同方向鎖住 |
| T-04-04 | mitigate | 明確 `:disabled="submitting"` + `if (submitting.value) return;` 雙重 guard;Test 22/23/24 |
| T-04-09(`fields` value) | mitigate | 只用 key 判斷欄位;Test 36 的 `not.toContain('must be greater than or equal to')` |
| T-04-09(`error.message`) | mitigate | `error.message` 字面零命中;Test 36 與 Test 39 的 11 個 code 逐一檢查 |
| T-04-03 | mitigate | `currentKey` 未綁進任何模板節點(全檔搜尋只出現在 `<script>` 區) |
| T-04-06 | mitigate | U-08 的版位由 Test 39 鎖住,且 `apiClient` 未改動 |
| T-04-07 | mitigate | Test 48:預檢通過仍被後端 409 拒絕,底部正確顯示 |
| —(預檢欄位) | mitigate | Test 50 的 `?raw` 整檔斷言 |
| —(result 數值來源) | mitigate | Test 30 的刻意矛盾 fixture |
| T-04-SC | accept | **零新增依賴**(`package.json` 不在本 plan 的 diff 清單內) |

**新增威脅面掃描:** 本 plan 未新增網路端點(`POST /api/v1/trades` 與 `GET /portfolio/holdings` 都已在 register 內)、未新增認證路徑、未新增檔案存取、未改動信任邊界的 schema。**無新旗標。**

## 誠實列出本 plan **未**涵蓋的事

- **視覺驗證** —— 三項人工檢視項目我做的是程式碼層檢視,**沒有開過瀏覽器**(詳見上方第 5 節)。
- **真實 CSRF / cookie 行為** —— jsdom 不實作 HttpOnly,測試裡的 `document.cookie` 是人工種下的。屬 **Phase 5 / VER-03**。
- **真實 network call 的證據** —— 全部 fixture 皆為 stub 的 `fetch`;judgment §3 要求的「API mode 必須看到真實 network call」尚未滿足,屬 Phase 5。
- **後端 409 的實際 payload 比對邏輯** —— 本 plan 消費的是 `TRADE_IDEMPOTENCY_KEY_REUSED` 這個 code 字面,沒有驗證後端在什麼條件下真的回它(那是 04-03 / 04-05 的範圍)。
- **三頁 post-trade refetch 與 fresh 高亮** → 04-12(本 plan 完全沒碰三個 portfolio 頁)。

## User Setup Required

None —— 純前端、零新增依賴、無外部服務。

## Next Phase Readiness

**Ready for 04-12:**

- `notifyTradeCreated(trade)` 已在成功與 replay 兩條路徑上都呼叫,`portfolioRevision` / `apiLastFill` / `lastCreatedTradeId` 三個訊號在一次成交後同時就位(Test 33 直接斷言三者的值)。04-12 的三頁 `watch` 可以直接消費。
- `OrderTicket` 自己已經是 `portfolioRevision` 的第一個消費端(holdings 快取失效),04-12 若要加 `if (live) return;` 的早退,可參照本檔的 watch 寫法。

**移交注意事項:**

1. **前端 repo 分支 `feature/phase-04-manual-trade-creation`,未 push;後端 repo 分支 `feature/phase-04-trade-idempotency`,未 push。本 plan 未開任何 PR。**
2. **`apiTypes.ts` 的 `AssetDto.volumeText` 型別仍與後端不符**(04-10 已記錄,本 plan 未碰)。
3. **`OrderTicket.vue` 已經有兩套 SELL 預檢**:mock mode 的 `sellPrecheckError`(同步讀 mock store 的 reactive 視窗,錯誤訊息是硬編碼英文)與 API mode 的 `oversellError`(走 `listHoldings` + i18n)。04-13 的雙 mode 收尾若要統一,請留意 mock 那條的英文字串是 04-09 的既有物,不是本 plan 引入。
4. **前端 repo 不在 GSD 的 worktree 隔離範圍內**,所有前端 plan 共用同一個工作樹;同一 wave 排兩個 FE plan 會互相踩踏(04-06 起連續三份 SUMMARY 都提過這件事)。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-08-16*

## Self-Check: PASSED

- **5 個 commit hash**(`11475cc` / `0f411c0` / `49ada21` / `4556422` / `e8d1f35`)皆已於前端 repo `git log` 驗證存在,且 `test(...)` 一律在對應的 `feat(...)` 之前。
- **2 個修改檔案**皆存在;`git log --name-only` 確認五個 commit 合計只動這 2 個檔案;`git status --porcelain` 為空。
- **測試數已核對:** `grep -c "^\s*it("` → 63,`it.skip|it.todo` → 0,與 `63 passed` 一致;全套 338 = 309(04-10 收尾)+ 29。
- **本 SUMMARY 的所有指令輸出均為本次實際執行所得**,RED 的紅綠比例為實測值未經美化;一寫就綠的 9 條已逐條說明,含一條明確標示為假綠的 Test 36。
- **未修改** `STATE.md` / `ROADMAP.md`;**未觸碰**任何後端原始碼;**未執行** `git push`;**未開** PR。
