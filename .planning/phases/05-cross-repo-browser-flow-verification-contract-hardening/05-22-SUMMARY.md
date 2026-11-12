---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 22
subsystem: frontend-api-contract
tags: [big.js, decimal, asset-quote, watchlist, live-quotes, order-ticket, wire-string, vitest, playwright]

requires:
  - phase: 05-21
    provides: "mock 回傳 domain 不 structuredClone、mapper 每次建立新物件;避免 service 間循環 import"
  - phase: 05-19
    provides: "OrderTicket.priceInputText 交接點(number latestPrice → 預填文字);價格欄為文字輸入"
  - phase: 05-16
    provides: "decimal.ts(parseNullableDecimal / toWire / numberToWire / ROUND_HALF_UP / ZERO);DecimalFields;moneyFormat 接受 Decimal;Wave 2 前端共同規約"
  - phase: 05-14
    provides: "後端 AssetDto 五個報價欄掛 @DecimalString,JSON 字串(一般記號)"
provides:
  - "apiTypes:AssetDto.latestPrice / change / changePercent / high / low 改 string | null;domain Asset = DecimalFields<AssetDto, 五個報價欄>"
  - "mapper:export toAsset(wire)(marketApi.ts);http 與 mock 共用;wire 混入 JSON number 丟 TypeError"
  - "介面:MarketApi.searchAssets 回 PaginatedResponse<Asset>"
  - "watchlistApi:WatchlistDto 維持 wire;新增 domain WatchlistRecord { id, name, items: Asset[] } 與 export toWatchlistRecord;WatchlistApi 七個方法全回 domain;mock 先以 numberToWire 組 wire 再 map"
  - "useLiveQuotes:LiveQuoteRow = Asset & { quoteSource };overlayTick 以 Decimal 計算(minus / times / div().round(2, ROUND_HALF_UP) / cmp);number 版 roundHalfUp 與 CHANGE_DIGITS 已刪除"
  - "useQuoteRefresh(rows: Ref<readonly Asset[]>)"
  - "Watchlist 頁 / Overview 觀察清單卡:顯示函式改收 Asset,正負用 Decimal gt / lt,數字經 moneyFormat.fmtMoneyAmount"
  - "WatchlistPicker / Chart / Markets:清單型別 WatchlistRecord(無邏輯變更)"
  - "OrderTicket:selected / options 為 Asset;numOrDash / pctOrDash / signedNumOrDash / changeColor 收 Decimal | null、經 moneyFormat;priceInputText = toWire(latestPrice),缺價預填 '0'(05-19 交接點已刪除)"
  - "HoldingEventDialog:pickedAsset / symbolState / options 型別改 Asset"
  - "交接點(05-22 → 05-23):useLiveQuotes.ts overlayTick 開頭 `parseDecimal(numberToWire(tick.price))`(唯一一處)"
affects: [05-23, 05-24, 05-25]

actuals:
  tokens: 16200
  tasks: 3
  commits: 3
commits: 3
plan_head_before: "frontend c334f82b633832bdae46153a0032e777df155eb1"
plan_head_after: "frontend 1bf80c59b0aa1daa055a01b3183c21b9b1428236"

tech-stack:
  added: []
  patterns:
    - "共用 wire 型別的 domain(WatchlistDto.items 與 GET /assets 都是 AssetDto)由同一個 mapper(toAsset)轉換;watchlistApi import marketApi,反向不 import,無循環"
    - "報價列疊加:REST 側已是 Decimal、WS 側(LiveTick)仍是 number 時,只在 overlayTick 一處 number → Decimal,其餘計算全在 Decimal"
    - "帶號顯示保留「正負依未捨入原值、本體為絕對值的固定位數」語意(0.00000001% → +0.00%,class up),與 Phase 5 前的 number 版相同"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/marketApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/marketApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/watchlistApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/watchlistApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/testing/watchlistFetchDouble.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Watchlist.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/composables/useQuoteRefresh.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Chart.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Markets.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts

key-decisions:
  - "changePercent = change × 100 ÷ previousClose,`div` 後 `round(2, ROUND_HALF_UP)`(與 Positions.holdingWeight 同寫法);div 中間結果依 decimal.ts 的 DP = 20 捨入,理論上有二次捨入,只在第 21 位以後恰好落在 .xx5 邊界時才可能與後端 divide(…, 2, HALF_UP) 不同,視為可接受"
  - "change 不捨入(後端 change 本身不捨入);number 版的 CHANGE_DIGITS = 8 只是清浮點殘差,Decimal 不需要,連同 number 版 roundHalfUp 一起刪除並在 JSDoc 說明"
  - "Watchlist / Overview 原本以 `toLocaleString('en-US', …)` 格式化,改經 fmtMoneyAmount 後 locale 為執行環境預設(與全站 fmtNum / moneyFormat 相同);zh-TW 與 en-US 的千分位 / 小數點相同,既有渲染斷言不變"
  - "OrderTicket.pctOrDash 原本是 data.ts fmtPct(`toFixed(2)`,無千分位);改經 moneyFormat 後 ≥ 1000% 會出現千分位(1,234.56%),其餘輸出相同(含 0 補 +、-0.004 → -0.00%)"
  - "缺價預填維持 '0':送出鈕因價格不 > 0 停用,使用者需自行輸入成交價,前端不猜價格;新增測試鎖住"
  - "mock listKlines 的走勢起點直接取 data.ts 的 number 價格(mock-only 畫面資料),不從 domain Asset 轉回 number"
  - "WatchlistPicker.test 的 fixture 維持 wire(同一份也餵給 fetch double),prop 經 toWatchlistRecord 轉成 domain 再傳入"

patterns-established:
  - "頁面 / 元件 / composable import AssetDto / WatchlistDto 為 0(acceptance grep);測試檔可用 wire 型別組 fetch stub"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "toAsset:'187.45000000' → Decimal(toWire '187.45')、null → null、123456789012.12345678 / -0.00000001 逐字;http searchAssets 9007199254740993.01 逐字;五個報價欄任一為 JSON number → toAsset 與 searchAssets 丟 TypeError;mock 搜尋結果為 Decimal 且 toWire 等於 numberToWire(data.ts 值);mock 每次回新物件"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/marketApi.test.ts#marketApi — AssetDto 字串契約 → Asset(05-22 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T2
    description: "watchlistApi:http list 報價字串 → Decimal(完整精度、null 維持);addItem / removeItem / reorder / rename / create 皆回 domain;wire number 丟 TypeError;mock 列 / FX / 債券 / 未知代號以 toWire 比對;既有排序 / 加入 / 移除 / clone 測試綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/watchlistApi.test.ts#watchlistApi — 報價字串契約 → WatchlistRecord(05-22 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T3
    description: "Watchlist 頁:既有價格 / 漲跌 / 漲跌幅與 BOND 分流斷言不變;9007199254740993.005 → 9,007,199,254,740,993.01、2.345 → 2.35、-2.355% → -2.36%、0.00000001% → +0.00%(up)、債券 -0.0000001 → -0.000;無科學記號;源碼守門"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Watchlist.test.ts#05-22(D-05 / D-08)兩個測試"
        status: pass
    human_judgment: false
  - id: T4
    description: "useLiveQuotes:100 / 2 + tick 103 → change 5、changePercent 5.1;218.4 − 198 = 20.4(無殘差)、大額 change 0.87654323;-0.125 → -0.13、0.125 → 0.13;前收 0 時 changePercent 維持;high / low 以 cmp 取極值(含相等);既有 quoteSource / lastUpdatedAt 測試綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/composables/useLiveQuotes.test.ts#useLiveQuotes — Decimal 疊加(05-22 / D-08)"
        status: pass
    human_judgment: false
  - id: T5
    description: "Overview 觀察清單卡:大額價格、捨入後為 0 依原值帶號(-0.00%,dn)、債券 3 位、缺價 —(na)、tick 103 → +5.10%(ws);源碼守門不 import wire 型別、顯示函式無 Math.abs / toLocaleString / Number()"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Overview.test.ts#05-22(D-08)兩個測試"
        status: pass
    human_judgment: false
  - id: T6
    description: "OrderTicket:預填 123456.12345678 / 0.0000005(無科學記號)/ 190.20000000 → 190.2;缺價預填 0、報價卡 —、不可送出;報價卡大額與 HALF_UP、漲跌 0 → +0.00、色彩依 Decimal;搜尋選項 1,234.50 / +0.01% / -0.00%;源碼守門(不 import AssetDto、預填不經 String() / parseDecimalInput、不用 fmtNum / fmtPct);既有 102 個測試綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/OrderTicket.test.ts#OrderTicket 標的報價 — domain Asset(05-22)"
        status: pass
    human_judgment: false
  - id: T7
    description: "layout.spec(watchlists / assets stub 改字串後版面不變)"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 22: 標的報價字串契約與 Decimal 計算 Summary

**`AssetDto` 的五個報價欄改成 wire 字串,新增的 `toAsset` 轉成 Decimal domain `Asset`,搜尋與觀察清單(`WatchlistRecord`)兩條路徑共用。Watchlist 頁、Overview 觀察清單卡、OrderTicket 報價卡 / 搜尋選項 / 價格預填都改以 Decimal 判斷、經 moneyFormat 顯示。`useLiveQuotes` 的 tick 疊加在 Decimal 內計算漲跌與漲跌幅(HALF_UP 2 位)。05-19 留在 `OrderTicket.priceInputText` 的交接點已刪除,新的唯一交接點在 `useLiveQuotes.overlayTick`,由 05-23 收回**

## Performance

- **Duration:** 約 25 min
- **Started:** 2026-11-11T05:13Z
- **Completed:** 2026-11-11T05:38Z
- **Tasks:** 3/3
- **Files modified:** 23(與 plan frontmatter 相同,沒有超出)

## Accomplishments

- `apiTypes.ts`:`AssetDto` 報價欄改 `string | null`,JSDoc 改寫(Phase 5 D-05 後與 `KlineDto` 一致皆為字串);新增 `Asset`。`KlineDto` 的「與 AssetDto 相反」說明一併更新。
- `marketApi.ts`:新增 `toAsset`;http `searchAssets` 把 `items` 逐列轉換;mock 由 `data.ts` 經 `numberToWire` 組 wire 再 map,每次回新物件。
- `watchlistApi.ts`:新增 `WatchlistRecord` / `toWatchlistRecord`(items 經 `toAsset`);http 七個方法與 mock 全部回 domain;mock 的 `toDto` 改為 `toRecord`(store → wire → domain)。
- `useLiveQuotes.ts`:`overlayTick` 改用 Decimal。`previousClose = latestPrice − change`、`change = price − previousClose`(精確,不捨入)、`changePercent = change × 100 ÷ previousClose` 捨入到 2 位 HALF_UP、high / low 以 `cmp` 取極值。`roundHalfUp(number)` 與 `CHANGE_DIGITS` 已刪除。
- `Watchlist.vue` / `Overview.vue`:四個顯示函式改收 `Asset`,缺價直接判 `null`,正負用 `gt` / `lt`,數字經 `fmtMoneyAmount`。
- `OrderTicket.vue`:報價卡與搜尋選項的格式化函式改收 `Decimal | null`、經 moneyFormat;不再 import `data.ts` 的 `fmtNum` / `fmtPct`。`priceInputText` 改為 `price === null ? '0' : toWire(price)`。
- `WatchlistPicker.vue` / `Chart.vue` / `Markets.vue` / `HoldingEventDialog.vue`:只改型別註記。
- `e2e/support/portfolioStubs.ts`:assets / watchlists fixture 報價改字串。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)+ 型別閉包切換**:`b6ba918` `feat(05-22): 標的報價改為字串契約並以 Decimal 對應`。Task 1 的檔案加上 Task 2 / 3 的所有原始碼變更(useLiveQuotes / useQuoteRefresh / Overview / WatchlistPicker / Chart / Markets / OrderTicket / HoldingEventDialog)、所有受影響測試的 fixture,以及 marketApi / watchlistApi / Watchlist 的新測試。原因見 Deviations #1。
2. **Task 2**:`ce642ee` `feat(05-22): 報價列疊加與觀察清單卡改用 Decimal`。useLiveQuotes 的 Decimal 疊加測試、Overview 觀察清單卡精度測試與源碼守門。
3. **Task 3**:`1bf80c5` `feat(05-22): 標的搜尋消費者改用 Decimal,layout stub 改字串`。OrderTicket 的精度 / 預填 / 缺價 / 源碼守門測試,以及 portfolioStubs 字串化。

完整 SHA:
- `b6ba918abfb256bf97c72dc6ea0a1ef7ad79bb46`
- `ce642ee8fc4fa63d03c8c65b016812a50a28b12f`
- `1bf80c59b0aa1daa055a01b3183c21b9b1428236`

plan 開始前 HEAD 為 `c334f82b633832bdae46153a0032e777df155eb1`;`git rev-list --count c334f82..HEAD` = 3。這 3 個 commit 只存在於前端 repo 的歷史。

## Gate(最後一個 commit 的程式狀態)

- `npm run lint`:0 errors、38 warnings(與 baseline 相同)
- `npm test`:66 files / 1490 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1490 tests passed
- `npm run build`(vue-tsc --noEmit + vite build):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed
- b6ba918 本身也跑過 lint(0 errors / 38 warnings)、vue-tsc(0 errors)、兩種 mode 各 1478 tests,全部通過;ce642ee 跑過受影響的 5 個測試檔(兩種 mode)、eslint 與 vue-tsc
- Tracer 閘門:擴展到 Task 2 之前,Task 1 的 `<verify>`(marketApi.test + watchlistApi.test + Watchlist.test,120 tests)重跑並通過
- portfolioStubs.ts 另以 `tsc --strict` 單檔檢查通過(e2e 不在 vue-tsc 的 include 內)
- 驗收 grep:AssetDto 區塊 `(latestPrice|change|changePercent|high|low): number` 0;`export function toAsset` 1;`Promise<PaginatedResponse<AssetDto>>` 0;useLiveQuotes `Math.(max|min)(row.(high|low)` 0;`row.latestPrice - row.change` 0;useLiveQuotes 非註解行 `numberToWire(` 1;WatchlistPicker / Chart / Markets 非註解行 `WatchlistDto` 0;pages / components / composables import `AssetDto|WatchlistDto` 0
- 禁止事項(judgment):觸及的頁面 / 元件 / composable 沒有對報價做 `Number()` 或算術運算子;剩下的 `Number(` 只在 HoldingEventDialog 的比例(Integer)解析,`Math.max` 只用在時間戳與列數

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界依型別閉包重切,Task 1 涵蓋 plan 原本放在 Task 2 / 3 的原始碼與 fixture**
- **Found during:** Task 1
- **Issue:** `AssetDto` 改字串、`searchAssets` / `WatchlistApi` 改回 domain 之後,Watchlist 頁把 `Asset[]` 傳給 `useLiveQuotes` / `useQuoteRefresh`(仍收 `AssetDto`)就會型別錯誤;OrderTicket、HoldingEventDialog、Overview、WatchlistPicker、Chart、Markets 也同時編譯失敗。測試檔在 vue-tsc 的 include 內,number fixture 也會讓 build 紅、讓 `parseDecimal` 在執行期丟錯。照 plan 切的話 Task 1 的 commit 無法 build。
- **Fix:** b6ba918 包含型別切換與所有消費者(含 `overlayTick` 的 Decimal 計算,因為 Decimal 不能用 `-` / `Math.max`),以及所有受影響測試的 fixture。ce642ee / 1bf80c5 只放新增的精度測試與 layout stub。三個 commit 都能 build,兩種 mode 的測試都綠。做法與 05-19 / 05-21 相同。
- **Committed in:** `b6ba918`、`ce642ee`、`1bf80c5`

**2. [Rule 3 - Blocking] OrderTicket.test 的 `advance()` 改跑 `flushAsync(16)`**
- **Found during:** Task 1
- **Issue:** http `searchAssets` 在 apiClient 之後多一段 `toAsset` 映射,非同步鏈多一個 microtask。OrderTicket typeahead 的 Test 5–8 在假計時器下用 `flushAsync()` 預設 6 輪,剛好不夠(改成 `.then` 也一樣差一輪)。
- **Fix:** helper 改跑 16 輪,與 HoldingEventDialog.test 的同名 helper 一致(LEARNINGS 2026-11-07 已記載假計時器下要多跑幾輪)。產品碼維持 async/await,與 portfolioApi 寫法一致。
- **Committed in:** `b6ba918`

### 計畫文字與既有程式不同

- 介面清單中的行號(`apiTypes.ts:242-262` 等)與實際位置不同(05-17 ~ 05-21 新增了型別),依名稱定位,不影響內容。
- `Chart.test.ts` / `Markets.test.ts` 不需修改(它們經 fetch double 拿 wire,fetch double 已改字串),兩種 mode 都通過。
- 版面測試在 b6ba918 與 1bf80c5 之間會因 stub 仍是 number 而失敗(頁面經 `parseDecimal` 丟錯);plan 本來就把 stub 放在 Task 3,所以照做。最終 commit 101 passed。

---

**Total deviations:** 2 個自動修正(Rule 3 commit 邊界、Rule 3 測試 helper 輪數)

## Deferred / Out of Scope

- WS `LiveTick` / `quoteStream` 的 Decimal 化依約由 05-23 處理。測試側的 tick 替身(`useLiveQuotes.test.ts` 的 `fakeStream().pushTick(symbol, price: number)`、`testing/fakeQuoteStream.ts` 的 `pushTick`)仍傳 number,05-23 一併改。
- 真後端 E2E spec 依約由 05-24 處理,沒動;`e2e/tests/` 內沒有引用 `latestPrice` 的 spec。
- `watchlistApi.ts` 與 `marketApi.ts` 各有一份 `mockMarketOf`(FX 幣別規則不同),屬既有重複,不在本 plan 範圍。

## Threat Flags

沒有新增安全面。T-05-36 已緩解:`toAsset` 對五個報價欄走 `parseNullableDecimal`(strict,number 丟 TypeError;marketApi / watchlistApi 都有測試);null 價格維持 null 並顯示 `—`(Watchlist、Overview、OrderTicket 都有測試,含「不當成 0%」);疊加以 Decimal 計算,changePercent 以 HALF_UP 捨入到 2 位。

## Known Stubs

無。

## Next Phase Readiness(交接)

- **05-22 → 05-23 交接點(唯一)**:`vue-app/src/composables/useLiveQuotes.ts` 第 38 行 `const price = parseDecimal(numberToWire(tick.price));`,以及第 3 行 import 中的 `numberToWire`。05-23 把 `LiveTick.price` 改成 Decimal 後,刪除這一行的轉換(改成 `const price = tick.price;`)與 `numberToWire` import,並以 `grep -v "^\s*\(\*\|//\)" src/composables/useLiveQuotes.ts | grep -c "numberToWire("` = 0 驗證;第 36–37 行的交接註解一起刪。
- 05-23 要一起改的測試替身:`useLiveQuotes.test.ts` 的 `fakeStream().pushTick(symbol, price: number)`,以及 Overview / Watchlist 測試使用的 `testing/fakeQuoteStream.ts` `pushTick`。斷言已用 `toWire` 比字面,tick 改成 Decimal 後期望值不變。
- `src/pages` / `src/components` / `src/composables` 非註解行引用 `numberToWire`:只剩 useLiveQuotes 這一處;OrderTicket 0(05-19 交接點已刪)。05-24 的源碼守門可以在 05-23 之後把三個目錄一起納入。
- domain 型別:`Asset` 從 `services/apiTypes` import;`WatchlistRecord` / `toWatchlistRecord` 從 `services/watchlistApi` import;`toAsset` 從 `services/marketApi` import。watchlistApi 依賴 marketApi,反向不要 import(避免循環)。
- 回傳含 Decimal 的 mock 資料時不要 `structuredClone`;market / watchlist 的 mock 每次都經 mapper 建立新物件。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 23 個檔案都已修改(`git diff --name-only c334f82..HEAD`:23 files)
- FOUND:`b6ba918`、`ce642ee`、`1bf80c5`(前端 repo,都是 HEAD 的祖先;`git rev-list --count c334f82..HEAD` = 3)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
