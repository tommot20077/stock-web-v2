---
phase: 04
slug: manual-trade-creation-idempotency-post-trade-refetch
status: draft
shadcn_initialized: false
preset: existing-stock-v2-shell
created: 2026-07-26
---

# Phase 04 — UI Design Contract

> Manual Trade Creation, Idempotency & Post-Trade Refetch 的視覺與互動契約。
> 由 gsd-ui-researcher 產出、gsd-ui-checker 驗證、planner 與 executor 消費。
>
> 上游決策來源:`04-CONTEXT.md`(D-01 ~ D-16)、`04-RESEARCH.md`(Q0 ~ Q12 / DP-1 ~ DP-14)、
> `01-UI-SPEC.md` / `02-UI-SPEC.md`(既有設計系統基線)、Phase 3 的 D-11 ~ D-16(區塊狀態慣例)。
> **本文件不重述上游已鎖定的決策,只把它們翻譯成可實作的視覺/互動契約,並補上上游未決的部分。**

---

## Design System

| Property | Value |
|----------|-------|
| Tool | none(未初始化 shadcn;Phase 1/2 UI-SPEC 已明文不授權任何 component registry) |
| Preset | existing Stock V2 app shell |
| Component library | none — 手寫 Vue SFC + scoped CSS |
| Icon library | 既有 inline 文字/emoji 字符(`✕` `✓` `↗` `↘`);**本階段不引入 icon 套件** |
| Font | `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif`(`src/styles.css:18`) |
| Base font size | `13px`(`src/styles.css:19`) |
| Radius token | `--radius: 12px`(`src/styles.css:5`);ticket 外框 `14px`、卡片 `10px`、控件 `8px` |

### 前端 repo 位置(本 repo 沒有任何 Vue 檔)

| 事實 | 我實際跑過的驗證 |
|------|-----------------|
| 前端專案根 | `D:\end\workspace\vue\stock-v2\vue-app`(`ls vue-app` → `src/` `package.json` `vite.config.ts`) |
| 目前分支 | `develop` @ `a03e030`(`git branch --show-current` / `git log --oneline -1`) |
| **無 shadcn / Tailwind / PostCSS** | `ls vue-app/components.json vue-app/tailwind.config.* vue-app/postcss.config.*` → 三者皆 `No such file or directory` |
| 設計 token 唯一來源 | `src/styles.css`(31 行,全檔實讀):`--bg --surface --surface2 --fg --fg-dim --fg-mute --border --accent --up --dn --neg --radius` |
| **前端沒有 asset / market adapter** | `grep -rn "api/v1/assets\|klines\|market/" src/` → **零命中**(我本 session 實跑) |
| `Markets.vue` 仍讀本地假資料 | `sed -n '129p' src/pages/Markets.vue` → `import { SYMBOLS, CRYPTO, FX, BONDS, ... } from '../data';` |
| `RuntimeApiClients` 現有欄位 | `pageApiClients.ts:9-17` 實讀 → `auth / aiAccess / backtest / ops / portfolio`(無 `trading`、無 `asset`、無 `market`) |

### shadcn 初始化閘門(已執行,結論:不初始化)

`components.json` 不存在(上表已驗證)。**不重新詢問使用者**,因為上游已有兩份明文決策:

- `01-UI-SPEC.md:216` — 「No component registry adoption is authorized by Phase 1. Any future library introduction must happen in a dedicated frontend phase with tests and visual review.」
- `02-UI-SPEC.md:230` — 「No external component blocks, icon packages, or UI registries may be introduced by Phase 2.」

Phase 4 是**改寫既有 SFC**(`OrderTicket.vue`),不是新建設計系統;導入 registry 會讓本階段同時承擔「冪等後端契約 + 前端 adapter 新建 + 設計系統遷移」三件事。**Tool: none 續用。**

### 本契約涵蓋的畫面

| 介面 | 檔案 | 變動幅度 |
|------|------|---------|
| Order ticket(主戰場) | `src/components/OrderTicket.vue`(555 行,全檔實讀) | **API mode 幾近重建** |
| Positions 頁 | `src/pages/Positions.vue` | post-trade refetch + `fresh` 高亮接線(D-10/D-13) |
| Trades 頁 | `src/pages/Trades.vue` | 同上 + 「不在目前檢視範圍」提示(D-11) |
| Overview 頁 | `src/pages/Overview.vue` | post-trade refetch(D-10) |
| 全域 toast | `src/components/Toast.vue` | 只改文案(不得出現「已成交」語意) |
| 全域 session banner | `src/components/SessionBanner.vue` | **不改** — 邊界見 §錯誤分派 |

---

## Spacing Scale

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4px | 標籤與輸入框間距、診斷列 gap、`.seg` 內襯 |
| sm | 8px | 控件內距(垂直)、side toggle gap、欄位錯誤與輸入框間距 |
| md | 12px | 兩欄輸入 gap、控件內距(水平)、卡片列內距 |
| lg | 16px | 卡片內距、header/footer 垂直內距、區段間距 |
| xl | 24px | ticket 主體內距、two-col 欄距、header/footer 水平內距 |
| 2xl | 32px | review / result 畫面的垂直內距 |
| 3xl | 48px | 只用於 result 畫面上方留白;**禁止**用於 ticket 表單區 |

**Exceptions(允許不是 4 的倍數):**

1. `1px` 邊框與 `1px 2px` 陰影偏移。
2. 裝飾性 step dot:`6px` 圓點 / `20px` 展開膠囊(`OrderTicket.vue:434-435`)。
3. 控件尺寸下限(沿用 `02-UI-SPEC.md:46`):input / 按鈕 `min-height: 36px`;`.inp.big` 與主要 CTA `min-height: 44px`;icon-only 關閉鈕觸控目標 `44px`。
4. `.quote-empty` 的 `40px` 垂直內距(既有值,恰為 4 的倍數,保留)。
5. 圓角 `8px / 10px / 14px` 與 `--radius: 12px` 屬 radius,不受 spacing scale 約束。

### 必須遷移的既有值(重建範圍內,逐條 file:line)

現行 `OrderTicket.vue` 的 scoped CSS 有 11 處不是 4 的倍數。**重建這些區塊時一併改為下表右欄**;未列出的既有值已合規,不要動。

| 選擇器 | 現值(`OrderTicket.vue`) | 目標值 |
|--------|------------------------|--------|
| `.hd` | `padding: 16px 22px`(`:430`) | `16px 24px` |
| `.hd-l` | `gap: 14px`(`:431`) | `gap: 12px` |
| `.body` | `padding: 22px`(`:440`) | `24px` |
| `.lab` | `margin: 14px 0 6px`(`:443`) | `16px 0 8px` |
| `.inp` | `padding: 9px 12px`(`:448`) | `8px 12px` + `min-height: 36px` |
| `.inp.big` | `padding: 11px 14px`(`:452`) | `12px 16px` + `min-height: 44px` |
| `.sym-meta` / `.sym-tag` | `margin-top: 6px` / `padding: 1px 6px`(`:456-457`) | `8px` / `2px 8px` |
| `.sym-row` | `padding: 10px 14px`(`:463`) | `12px 16px` |
| `.side-toggle` / `.side-btn` | `gap: 6px` / `padding: 11px`(`:467-469`) | `8px` / `12px` |
| `.seg` / `.seg-btn` | `padding: 3px; gap: 2px` / `padding: 7px 14px`(`:475-477`) | `4px; gap: 4px` / `8px 12px` |
| `.quote-card` | `padding: 14px 16px`(`:484`) | `16px` |
| 走勢圖容器 | `height: 90px; margin: 14px 0 10px`(`:107`) | `height: 96px; margin: 16px 0 8px` |
| `.summary` | `margin-top: 14px; padding-top: 14px`(`:491`) | 兩者皆 `16px` |
| `.form-error` | `padding: 8px 10px`(`:495`) | `8px 12px` |
| `.rev-grid > div` | `padding: 13px 16px`(`:506`) | `12px 16px` |
| `.ft` | `padding: 16px 22px`(`:547`) | `16px 24px` |
| `.btn-accent` / `.btn-ghost` | `padding: 10px 22px` / `10px 18px`(`:548,553`) | `8px 24px` / `8px 16px`,兩者 `min-height: 44px` / `36px` |

Positions / Trades / Overview 的既有版面**不在遷移範圍**(Phase 3 已驗收);本階段只新增 §Post-Trade Refetch 定義的 refresh 指示與 `fresh` 標記。

---

## Typography

新建與重建的 ticket 介面**只允許下列 4 個字級與 2 個字重**。

| Role | Size | Weight | Line Height | 用在哪 |
|------|------|--------|-------------|--------|
| Label | 12px | 600 | 1.35 | 欄位標籤、報價卡小標、診斷列、`.sym-tag`、pill |
| Body | 13px | 400 | 1.45 | 輸入值、下拉列、摘要列、錯誤文案、footer 按鈕 |
| Heading | 16px | 600 | 1.35 | ticket 標題、review/result 標題 |
| Display | 20px | 600 | 1.25 | 報價卡最新價、review 的「買進 10 AAPL」、result 的「交易已記錄」 |

**Constraints:**

- `letter-spacing` 一律 `0`。現行 `.lab`(`:443` `letter-spacing:.5px` + `text-transform:uppercase`)與 `.qm-l`(`:488` `.4px`)在重建時取消 —— 表單必填標籤不該用全大寫微字級,那是最難掃讀的組合。
- 金額、數量、價格、交易編號一律加 `.num`(`styles.css:25` `font-variant-numeric: tabular-nums`),避免送出前後數字跳動。
- 交易編號是 36 字元 UUID:必須 `overflow-wrap: anywhere`,**不得**截斷或以 `text-overflow: ellipsis` 隱藏尾段(它的唯一用途是除錯回報)。`error.code` 與 traceId 同規則(沿用 `Positions.vue:861` `.block-error .details span { overflow-wrap: anywhere; }`)。

### 必須改掉的既有字級(重建範圍內)

| 現值(`OrderTicket.vue`) | 目標 | 理由 |
|------------------------|------|------|
| `.hd-ttl` `15px/600`(`:432`) | `16px/600` | 併入 Heading |
| `.lab` `11px/500` uppercase(`:443`) | `12px/600` 無 transform | 併入 Label,取消第 5 個字級 |
| 報價卡最新價 `24px/600`(`:99`) | `20px/600` | 併入 Display |
| `.qm-l` `10px`(`:488`) / `.sym-tag` `10px`(`:457`) / 下拉副標 `11px`(`:41,45`) | 全部 `12px` | 10-11px 在 13px 基準下屬不可掃讀微字 |
| `.big-side` `28px`,`letter-spacing:-0.6px`(`:502`) | `20px`,`letter-spacing:0` | 併入 Display |
| `.filled-ttl` `22px`,`-0.4px`(`:543`) | `20px`,`0` | 同上 |
| `.seg-btn` `12px/500`(`:478`) / `.side-btn` `13px/600`(`:470`) | `12px/600` / `13px/600` | 字重收斂為 400/600 |

---

## Color

沿用 `src/styles.css` 的既有 palette,**不新增任何顏色變數**。

| Role | Value | Usage |
|------|-------|-------|
| Dominant (60%) | `var(--bg)`(`#fafaf9` / dark `#0c0d10`) | 頁面底色;ticket 遮罩 `rgba(0,0,0,0.42)` + `blur(6px)` |
| Secondary (30%) | `var(--surface)` / `var(--surface2)` | ticket 面板、報價卡、輸入框底、footer、下拉、骨架列 |
| Accent (10%) | `var(--accent)`(`#ff6600`) | 見下方 reserved 清單 |
| Destructive | `var(--dn)`(`#dc2626`) | 錯誤與 SELL 語意,見下方 |
| Semantic up | `var(--up)`(`#16a34a`) | BUY 語意與「已記錄」成功標記 |

**Accent reserved for(明確列舉,不得擴散):**

1. 推進按鈕「確認內容 →」的底色(`.btn-accent`)。
2. 輸入框 focus 邊框(`.inp:focus`,`:451`)。
3. step dot 的當前/已完成狀態(`:435-436`)。
4. 報價卡走勢線與其填色(`LineChart :fill="var(--accent)"`)。
5. `.sym-tag`(assetType 標籤)底色。
6. Trades / Positions 的 `fresh` 高亮動畫底色(`color-mix(in oklch, var(--accent) 22%, transparent)`,`Trades.vue:463`)。

**禁止**:不得把 accent 用於全部連結、全部 pill、所有 badge、refresh 指示、或任何錯誤/診斷文字。

**語意色的使用規則(這裡有一個必須講清楚的重疊):**

`var(--dn)` 同時承擔兩個語意 —— **SELL 方向**與**錯誤**。因為 ticket 上兩者可能同時出現(賣出時吃到 oversell 錯誤),必須靠非顏色線索區分:

| 用途 | 視覺形狀 | 非顏色線索 |
|------|---------|-----------|
| SELL 方向 | side toggle active(`rgba(220,38,38,0.10)` 底 + `--dn` 邊框,`:473`);送出鈕 `.btn-accent.sell` 實心 `--dn` | 文字「賣出」+ `↘` 字符(`:59`) |
| 錯誤 | `.form-error` `rgba(220,38,38,0.10)` 底 + `--dn` 文字(`:494-498`);欄位錯誤同色 12px 文字 | 一律有文字說明 + `error.code` 診斷列 + `role="alert"` / `aria-invalid` |

`var(--up)` 用於:BUY side toggle active(`:472`)、`.btn-accent.buy` 送出鈕、result 畫面的 `✓` 圓標(`:537`)。BUY/SELL **不得只靠顏色**區分 —— 現有的 `↗ 買進` / `↘ 賣出` 文字+字符組合必須保留。

---

## Copywriting Contract

所有字串經 `src/i18n.ts` 新增,**zh 與 en 兩邊都要加**(`i18n.ts:4` 是扁平 `Record<Lang, Record<string,string>>`;`i18n.test.ts` 已有「兩語言 key 都存在且不等於 key 本身」的斷言樣板,新 key 建議一併納入該清單)。命名沿用扁平 camelCase。

### 主要動作與標題

| Element | Copy(zh / en) | i18n key |
|---------|---------------|----------|
| **Primary CTA** | `記錄交易` / `Record trade` | `recordTrade` |
| 推進按鈕(ticket → review) | `確認內容 →` / `Review details →` | `reviewTrade` |
| 送出中按鈕標籤 | `記錄中…` / `Recording…` | `recordingTrade` |
| ticket 步驟標題 | `記錄交易` / `Record trade` | `recordTrade`(複用) |
| review 步驟標題 | `確認內容` / `Review details` | `reviewTrade`(去掉箭頭後複用) |
| result 步驟標題 | `交易已記錄` / `Trade recorded` | `tradeRecorded` |
| 返回編輯 | `← 返回修改` / `← Back to edit` | `backToEdit` |
| 記錄下一筆 | `記錄下一筆` / `Record another` | `recordAnother` |
| 查看持倉 | 既有 `viewPositions` | — |
| 取消 | 既有 `cancel` | — |
| **Destructive confirmation** | `記錄交易:寫入後無法修改或刪除,請確認上方內容。` / `Record trade: this cannot be edited or deleted once written. Check the details above.` | `tradeIrreversibleNote` |

> **禁止用語(judgment §1 + D-09)**:「下單 / place order」、「已成交 / filled」、「成交均價 / avg fill price」、「路由撮合 / routing」、「委託 / pending」、「取消委託 / cancel」、「有效期 / TIF」不得出現在 API mode 的任何文案、toast、測試斷言或 aria 標籤中。
> 現有 i18n key `placeOrder`(`:59` 下單)、`filled`(`:57` 已成交)、`avgFillPx`(`:58` 成交均價)、`routingMatch`(`:57` 路由撮合)、`placing`(`:57` 送單中)**不得再被 `OrderTicket.vue` 引用**。刪除前 planner 必須 grep 是否有其他元件在用。

### 欄位標籤與說明

| Element | Copy(zh / en) | i18n key |
|---------|---------------|----------|
| 成交時間 | `成交時間` / `Executed at` | `tradeExecutedAt` |
| 成交時間說明 | `以本機時區記錄,不可晚於現在。` / `Recorded in your local time zone; cannot be in the future.` | `tradeExecutedAtHint` |
| 手續費說明(D-02 的誠實揭露) | `手續費會計入成本基礎,記錄後無法修改。` / `Fee is included in cost basis and cannot be changed after recording.` | `tradeFeeHint` |
| 可賣數量 | `可賣數量` / `Sellable qty` | `sellableQty` |
| 可賣數量載入中 | `正在讀取持倉…` / `Loading holdings…` | `sellableQtyLoading` |
| 可賣數量讀取失敗 | `無法讀取持倉,送出後仍會由伺服器檢查。` / `Could not load holdings; the server will still check on submit.` | `sellableQtyFailed` |
| 交易編號 | `交易編號` / `Trade ID` | `tradeId` |
| 備註 | 既有 `notes` | — |
| 手續費 / 數量 / 價格 / 標的 / 方向 | 既有 `fee` / `qty` / `price` / `symbol` / `side` | — |

### 空狀態

| Element | Copy(zh / en) | i18n key |
|---------|---------------|----------|
| 未選標的(報價卡) | 既有 `selectSymbol`(`請選擇標的` / `Select a symbol`) | — |
| 查無標的 | `找不到符合的標的` / `No matching symbol` | `symbolNoResults` |
| 查無**可交易**標的 | `這個關鍵字沒有可交易的標的,請換個關鍵字。` / `No tradeable symbol matches this search. Try another keyword.` | `symbolNoTradable` |
| 結果被截斷 | `僅顯示前 10 筆,輸入更完整的關鍵字可縮小範圍。` / `Showing the first 10 — refine your search to narrow it down.` | `symbolMoreResults` |
| 走勢圖無資料 | `無走勢資料` / `No chart data` | `quoteChartEmpty` |
| 可賣數量為 0 | `可賣數量:0` / `Sellable qty: 0` | 由 `sellableQty` + 數值組成,**不得**寫成「您未持有此標的」(後端 `total_quantity > 0` 過濾讓「從未持有」與「已全數賣出」不可分,`JdbcTradingRepository.java:210`) |

### 錯誤狀態

**欄位級(`error.fields` 的 key → 綁對應輸入框,D-16)。** `fields` 的 value 是英文 Bean Validation 預設訊息,**一律不得出現在 DOM**;下列文案由前端提供,並靜態寫出合法範圍(範圍抄自 `CreateTradeRequest` 的註解,不是從錯誤訊息解析)。

| `fields` key | Copy(zh / en) | i18n key |
|-------------|---------------|----------|
| `symbol` | `請從清單選擇有效的標的。` / `Pick a valid symbol from the list.` | `tradeErrSymbol` |
| `type` | `交易類型無效。` / `Invalid trade type.` | `tradeErrType` |
| `quantity` | `數量必須大於 0,最多 8 位小數。` / `Quantity must be greater than 0, with at most 8 decimals.` | `tradeErrQuantity` |
| `price` | `價格必須大於 0,最多 8 位小數。` / `Price must be greater than 0, with at most 8 decimals.` | `tradeErrPrice` |
| `fee` | `手續費不可為負。` / `Fee cannot be negative.` | `tradeErrFee` |
| `note` | `備註最多 500 字。` / `Note must be 500 characters or fewer.` | `tradeErrNote` |
| `executedAt`(前端自檢,不會來自 `fields`) | `成交時間不可晚於現在。` / `Executed at cannot be in the future.` | `tradeErrExecutedAt` |

**Ticket 底部(依 `error.code` 分派,絕不假設錯誤出現順序 —— Q0/PR #15 明文警告)。** 每一條都必須回答「發生什麼 + 下一步做什麼」:

| `error.code` | Copy(zh / en) | i18n key |
|-------------|---------------|----------|
| `TRADE_INSUFFICIENT_HOLDING` | `持倉不足,無法賣出這個數量,交易未記錄。請調整數量後再送出。` / `Not enough holding for this quantity; nothing was recorded. Adjust the quantity and submit again.` | `tradeErrOversell` |
| `ASSET_NOT_FOUND` | `這個標的不存在或目前不可交易,交易未記錄。請重新選擇標的。` / `That symbol does not exist or is not tradeable; nothing was recorded. Pick another symbol.` | `tradeErrAssetNotFound` |
| `TRADE_UNSUPPORTED_TYPE` / `TRADE_INVALID_QUANTITY` / `TRADE_INVALID_PRICE` | `有欄位不符合規則,交易未記錄。請檢查上方欄位後再送出。` / `Some fields are invalid; nothing was recorded. Check the fields above and submit again.` | `tradeErrValidation` |
| `VALIDATION_FAILED`(且 `fields` 為 null) | 同上 `tradeErrValidation` | — |
| `TRADE_CONFLICT` | `持倉在記錄期間被其他操作變更,交易未記錄。請直接再送出一次,**不會**建立重複交易。` / `Your holdings changed while recording; nothing was recorded. Submit again — this will not create a duplicate.` | `tradeErrConflict` |
| `TRADE_IDEMPOTENCY_KEY_REUSED` | `這次送出的內容與前一次重試不一致,交易未記錄。請確認欄位後重新送出。` / `This submission differs from the previous retry, so nothing was recorded. Check the fields and submit again.` | `tradeErrKeyReused` |
| 403 且非 CSRF(`ACCESS_DENIED` / `FORBIDDEN`) | `這個帳號沒有記錄交易的權限。` / `This account cannot record trades.` | `tradeErrForbidden` |
| `AUTH_CSRF_TOKEN_INVALID` / `AUTH_CSRF_TOKEN_MISSING` | `安全驗證失敗,交易未記錄。請重新整理頁面後再試。` / `Security check failed; nothing was recorded. Refresh the page and try again.` | `tradeErrCsrf` |
| 非 `ApiClientError`(fetch reject / 斷線) | `無法連線到伺服器,這筆交易可能尚未記錄。直接再送出一次不會建立重複交易。` / `Cannot reach the server; this trade may not have been recorded. Submitting again will not create a duplicate.` | `tradeErrNetwork` |
| 其他 | `發生未預期的錯誤,交易未記錄。` / `Something unexpected happened; nothing was recorded.` | `tradeErrUnknown` |
| 缺 header(理論上不可達,前端一律帶 key) | 走 `tradeErrValidation` | — |

> **文案硬規則 1**:除了 `tradeErrNetwork`,每一條都必須明說「交易未記錄」。這是防 D-12 點名的最糟失敗模式(使用者以為建立成功而不敢重試,或以為失敗而改欄位重送 → 換 key → 真的建出第二筆)。
> **文案硬規則 2**:`tradeErrNetwork` 是唯一「結果未知」的情境,所以它是唯一必須明講「重試不會建立重複交易」的文案 —— 這正是本階段冪等工作對使用者的可見價值。
> **文案硬規則 3**:`tradeErrKeyReused` **不得**說「重複的請求 / duplicate request」。那會讓使用者以為交易被建了兩筆,而事實相反(什麼都沒建)。
> **文案硬規則 4**:任何錯誤文案都不得回射 idempotency key、symbol 以外的使用者輸入,或後端 `error.message`(`code-standards.md:79-84`;反例 `BackfillController:105-106`)。

### Post-trade refetch 與提示

| Element | Copy(zh / en) | i18n key |
|---------|---------------|----------|
| 區塊更新中 | `更新中…` / `Refreshing…` | `portfolioRefreshing` |
| 區塊更新失敗(交易已成功) | `交易已記錄,但這個區塊未更新成功,以下數字可能不是最新。` / `Trade recorded, but this section failed to refresh — the values below may be out of date.` | `portfolioStaleAfterTrade` |
| 新交易不在當前檢視 | `已記錄,但這筆交易不在目前的篩選/排序範圍內。` / `Recorded, but this trade is outside the current filter and sort view.` | `tradeNotInCurrentView` |
| 新列標記(D-13,非顏色線索) | `新` / `New` | `freshBadge` |
| 成功 toast | `已記錄 賣出 10 AAPL @ 190.20`(格式:`{已記錄} {方向} {數量} {標的} @ {價格}`) / `Recorded SELL 10 AAPL @ 190.20` | `tradeRecordedToast` + 組字 |
| 讀取中 / 讀取失敗 / 重試 / 追蹤 ID | 既有 `loading` / `loadFailed` / `authRetry` / `authRequestId`(`i18n.ts:131,132,122,129`) | 複用,**不新增同義 key**(`i18n.test.ts:30-33` 已鎖此慣例) |

---

## Interaction Contract

### 1. Ticket 步驟機(DP-9 已裁定:**保留 review 步驟**)

**決定:保留 `ticket → review → result` 三步,移除 `placing` 作為獨立步驟。**

理由(DP-9 授權我裁量):
- `transactions` 是 append-only 帳本(V8 trigger 禁 UPDATE/DELETE),**寫錯改不回來**。不可撤銷的寫入需要一個確認關卡,這是 UI 設計裡少數「多一步是對的」情境。
- D-02 把 fee 改成使用者手動輸入、D-03 允許改 executedAt 之後,可打錯的欄位變多了,review 的價值比 mock 時代更高。
- D-09 收斂的是「送出流程」的假進度(routing/match),不是 ticket → review。RESEARCH DP-9 明文同意這個區分。
- review 同時是 D-14 `dirtySinceSubmit` 的自然邊界:review 畫面沒有可編輯欄位,所以「送出中的 payload」與「使用者看到的 payload」保證一致。

| 步驟 | step dot | 標題 | 主要內容 | Footer |
|------|---------|------|---------|--------|
| `ticket` | 1/3 | `記錄交易` | 左:表單;右:報價卡 + 摘要 | `取消` / `確認內容 →`(`:disabled="!canSubmit"`) |
| `review` | 2/3 | `確認內容` | Display 級「買進 10 AAPL」+ `rev-grid` 明細 + `tradeIrreversibleNote` | `← 返回修改` / `記錄交易`(送出中變 `記錄中…` 且 disabled) |
| `result` | 3/3 | `交易已記錄` | `✓` 圓標 + 後端 `TradeDto` 明細 | `記錄下一筆` / `查看持倉 →` |

- **step dots 從 4 顆改為 3 顆**(`OrderTicket.vue:8` 的 `v-for="(_, i) in 4"`)。
- 送出中**不切換步驟**,仍停在 `review`(這是與現況最大的差異:現況 `step='placing'` 會讓 footer 整塊被替換,導致送出鈕從 DOM 消失 —— 那是 Q6.2 指出的「靠副作用擋連點」,TRAD-04 要求明確的 guard)。
- `result` 畫面的 `rev-grid` 只能顯示後端回傳的 `TradeDto` 欄位:`id` / `type` / `quantity` / `price` / `fee` / `executedAt` / `symbol`。**不得**出現「成交均價」、「訂單號」、隨機 slippage、或任何前端計算的成交價。`estTotal` 可顯示,但必須標示為 `quantity × price`(不含 fee)且來源是回傳值而非表單值。
- **mock mode 也走同一個三步機**(D-09 未區分模式,且 judgment §1 點名 routing 用語)。mock 保留的只有 D-04 明列的四樣:訂單類型、TIF、交易後現金,以及 mock 的通知推送。

### 2. Symbol typeahead(D-01 + DP-5 + DP-12)

資料來源由本地 `data.ts` 換成 `GET /api/v1/assets?query=&page=0&size=10`,**必須走 `apiPaginatedRequest`**(該端點回 `ApiResponse<PageResponse<AssetDto>>`,`AssetController:24`)。

| 狀態 | 觸發 | 視覺契約 |
|------|------|---------|
| `idle` | 聚焦但未輸入 | 顯示第 0 頁前 6 筆(對齊現況 `:246` 的 `.slice(0,6)`),標題列 `熱門標的`?**不加**標題 —— 直接列出,避免宣稱一個後端沒有的排序語意 |
| `loading` | debounce 觸發後、回應前 | 下拉維持開啟,顯示 3 條 `.skeleton-row`(沿用 `Positions.vue:868-873` 的 `skeletonPulse`)+ 文字 `載入中…`;**不得**只有 spinner |
| `loaded` | 有 `tradeable === true` 的結果 | 列表最多 10 筆;每列:symbol(13/600)+ name(12/400 `--fg-dim`)+ 最新價(`.num`)+ 漲跌幅(`--up`/`--dn`);`latestPrice` 為 `null` 時顯示 `—` 而非 `NaN`(`AssetDto` 的 BigDecimal 可為 null,A7) |
| `truncated` | `totalElements > items.length` | 列表底部一條 `--fg-mute` 12px 說明列 `symbolMoreResults`。**不做無限捲動/分頁按鈕** —— combobox 內分頁是新機制且無前例;縮小關鍵字是更便宜的互動 |
| `empty` | 結果 0 筆 | `symbolNoResults` |
| `filtered-empty` | 有結果但全部 `tradeable === false` | `symbolNoTradable`。**必須與 `empty` 分開**,否則使用者會以為打錯字 |
| `error` | 4xx/5xx/斷線 | 下拉內顯示 `symbolSearchFailed` + 診斷列(`error.code` / `authRequestId` traceId)+ `authRetry` 按鈕。**不阻擋 ticket 其他欄位** |

- Debounce **250ms**;並且只採用最後一次請求的結果(遞增 request id 或 `AbortController`;`ApiRequestOptions` 已支援 `signal`,`apiClient.ts:32`)。慢網路下 `AAP` 的回應覆蓋 `AAPL` 的是使用者可見的錯誤,不是理論風險。
- `preset`(從 Overview/Markets/Chart/Positions/Analytics 的 `@order` 帶入)在 API mode 必須用 `query=<sym>` 解析;解析中 symbol 欄位顯示 `loading` 態,解析失敗顯示 `symbolNoResults` 並保持欄位可編輯,**不得**靜默留空或回退 `data.ts`。
- 只有「後端確認存在且 `tradeable`」的標的才能讓 `canSubmit` 成立(D-01)。

### 3. 報價卡與走勢圖(D-01 / D-16 例外分支)

報價卡的每一格都來自同一份 `AssetDto`(`latestPrice / change / changePercent / high / low / volumeText`),**不需要第二個請求**。走勢圖來自 `GET /api/v1/market/{symbol}/klines`。

| 區域 | loading | empty | error |
|------|---------|-------|-------|
| 報價卡數字 | 隨 symbol 選定即有值(同一份回應),無獨立 loading | 個別欄位為 `null` → 顯示 `—` | 不會單獨失敗 |
| 走勢圖(96px 高) | 該區域顯示單條 `.skeleton-row` 撐滿 + 12px `載入中…` | `quoteChartEmpty`,置中 `--fg-mute` 12px | `quoteChartError` + `error.code` / traceId + `authRetry`,同區域內 |

**硬規則:走勢圖的 loading / empty / error 一律不得阻擋送出。** 它是輔助資訊,不是交易前提;`canSubmit` 不得依賴 klines 狀態。這條要有測試鎖住(走勢圖 error 狀態下送出鈕仍可用)。

### 4. 表單欄位契約

| 欄位 | 控件 | 預設 | 約束與提示 |
|------|------|------|-----------|
| 標的 | `.inp.big` + combobox 下拉 | 空 / `preset` | 見 §2 |
| 方向 | 既有 side toggle | `BUY` 或 `preset.side` | 文字 + `↗`/`↘`,不只顏色 |
| 數量 | `type="number"` `inputmode="decimal"` | `0`(或 preset 預設值) | `min` 依 assetType 決定 step;上限提示走 `tradeErrQuantity` |
| 價格 | `type="number"` `inputmode="decimal"` | `AssetDto.latestPrice`,**可編輯** | D-04 連帶效果:MKT 鎖價機制移除,一律預填可改 |
| 手續費 | `type="number"` `inputmode="decimal"` | **`0`** | `min="0"`;下方常駐 `tradeFeeHint`(不是錯誤色,是 `--fg-dim` 12px) |
| 成交時間 | `type="datetime-local"` | 現在(本機時區) | `max` = 現在;送出時轉為帶 offset 的 ISO 字串;下方常駐 `tradeExecutedAtHint` |
| 備註 | `type="text"` 或 `textarea` | 空 | 500 字上限;取代原本 `note: tif === 'GTC' ? 'GTC' : ''` |

**API mode 隱藏(D-04):** 訂單類型(MKT/LMT)、TIF(DAY/GTC)、「交易後現金」。隱藏方式是 `v-if="live"`(mock 才渲染),**不是** disabled 或 `visibility: hidden` —— 隱藏的欄位不得留下空白版位。

**SELL 預檢(D-15):** side 切到 `SELL` 時載入一次 `portfolio.listHoldings()` 並在該 ticket 生命週期內快取;在數量欄位下方顯示 `可賣數量:N`(`.num`)。狀態:載入中 `sellableQtyLoading`、失敗 `sellableQtyFailed`(**失敗不阻擋送出**,後端仍是權威)。超量時顯示 `tradeErrOversell` 並讓 `canSubmit` 為 false。**預檢只讀 `symbol` 與 `totalQuantity` 兩個欄位**,不得用 `avgCost` 算任何損益預估。交易成功後(`notifyTradeCreated`)ticket 內的 holdings 快取必須失效。

### 5. 重複送出阻擋(SC 3 / TRAD-04)

**前端 guard 只是 UX;server-side 冪等才是防護(judgment §5)。前端要做到的程度定義如下:**

| 元素 | 送出中(`submitting === true`)的狀態 |
|------|--------------------------------------|
| 送出鈕 | `:disabled="submitting"` **必須明確存在**(現況 `:185` 完全沒有 `:disabled`);標籤換成 `記錄中…`;`min-width` 固定,標籤變化不得造成版位跳動 |
| 「返回修改」鈕 | `disabled` — 回到表單會讓 in-flight 的 payload 與畫面不一致 |
| 關閉 `✕` 與遮罩點擊 | 不可關閉(沿用現況 `:344,348` 對 placing 的處理) |
| 所有表單輸入 | `disabled`(即使當前在 review 步驟不可見,回退後也不得可編輯) |
| ticket 容器 | `aria-busy="true"` |
| 狀態播報 | 一個 `role="status" aria-live="polite"` 區域文字 `記錄中…`(**不得只有 spinner**) |

**為什麼要把整張 ticket 鎖住,而不只鎖送出鈕:** idempotency key 在「按下送出」那一刻產生(D-14),而 D-14 規則 2 說「改過任何欄位就換新 key」。若送出期間允許編輯,`dirtySinceSubmit` 的語意會變成「in-flight 的那次要不要換 key」——一個無解的競態。凍結欄位讓 D-14 的兩條規則保持互斥且可測。

**`dirtySinceSubmit` 的互動後果(DP-11):**

- 任何欄位的 `@input` / `watch` 設 `dirtySinceSubmit = true`;送出成功或送出開始時設 `false`。**不做 form 物件深比較** —— 「改了又改回來」在使用者心智模型裡是「我動過了」,他期待新 key。
- 送出失敗後:key **保留**。使用者按「再送出一次」沿用同一 key(同一次嘗試的重試)。
- 送出失敗後使用者改了任何欄位:key **丟棄**,下次送出產生新 key(新意圖)。
- 這條路徑必須有測試:`400 → 改數量 → 再送 → 成功建立`(**不是** 409)。這是 D-07 與 D-14 互鎖的驗收(Pitfall 11)。

### 6. 冪等命中(同一 key retry 回傳既有交易)的可見行為

**決定:使用者看到的是「成功」,與首次建立完全相同的 result 畫面與文案。不做「這筆已存在」的變體。**

理由:
- 目前的 API 契約**沒有**任何「這是 replay」的訊號 —— 後端回傳的就是既有 `TradeDto`。要區分就得新增 response header 或信封欄位,那是 API 契約 shape 變更,judgment §9 要求先問 Yuan,且不在 D-01 ~ D-16 範圍內。**planner 不得自行加。**
- 從使用者意圖看,「我要記錄這筆交易」已經達成。多一句「這筆已經存在」只會讓人懷疑自己是不是記了兩筆。
- result 畫面完全由回傳的 `TradeDto` 渲染,所以 replay 顯示的數字與首次逐格相同 —— 這本身就是冪等的正確可見證據。

**連帶契約:** replay 也要呼叫 `notifyTradeCreated(trade)`(再次 bump revision、再次設 lastFill)。重讀同一份資料無害,而少做會讓「網路失敗後重試成功」的使用者看不到 portfolio 更新。

### 7. 錯誤展示與分派

**版位:**

| 錯誤類型 | 版位 |
|---------|------|
| `error.fields` 有 key | 對應輸入框**下方** 12px `--dn` 文字,`aria-describedby` 關聯,欄位 `aria-invalid="true"`。同時自動把 ticket 退回 `ticket` 步驟(否則使用者在 review 看不到出錯的欄位) |
| 依 `error.code` 分派的其他錯誤 | ticket **底部**單一區域,`role="alert"`;沿用 `.form-error` 樣式(`rgba(220,38,38,0.10)` 底 + `--dn` 文字,8px 圓角,`8px 12px` 內距) |
| 401 / refresh 失敗 | **不在 ticket 顯示**,走全域 `SessionBanner`(Phase 3 D-13 / Phase 2 D-14);`apiClient` 的 `onRefreshFailed` 已負責 |
| CSRF 403 | **在 ticket 底部顯示**(D-16 明文)。這與 `02-UI-SPEC.md:144` 的「CSRF 失敗走全域 banner」不衝突,分界是:**app 啟動時的 CSRF bootstrap 失敗 → banner;單一 unsafe 請求被 CSRF 拒絕 → 該請求的發起處**。D-16 是更晚且更具體的決策,以它為準 |

**診斷資訊(SC 5)的呈現方式 —— 決定:常駐的低調單列,不用 `<details>`,不加複製按鈕。**

沿用 Phase 3 已驗收的形狀(`Positions.vue:95-100`、CSS `:857-861`):

```
[使用者可讀的錯誤文案]
TRADE_INSUFFICIENT_HOLDING   追蹤 ID 8f2c...        ← 12px, --fg-dim, flex-wrap, overflow-wrap: anywhere
[重試]
```

理由:
- **不用可展開區**:多一次點擊才能拿到 traceId,而回報問題的人本來就不知道要展開。Phase 2/3 兩階段都是常駐低調列,已有一致慣例。
- **不加複製按鈕**:那是新機制(clipboard API + 成功回饋 toast + 新 i18n + 失敗降級路徑),而 code 與 traceId 都是短字串、可直接選取。`user-select` 不得被禁用。
- **只露 `error.code` 與 `meta.traceId`**,不露後端 `error.message`(Phase 3 D-12)。非 `ApiClientError` 的情況 traceId 為 null → 該 span `v-if` 不渲染(不顯示 `null`)。

**`TRADE_IDEMPOTENCY_KEY_REUSED` 的特殊互動(必須實作,否則使用者會卡死):**

收到這個 code 時,前端**必須丟棄當前 idempotency key**(等價於設 `dirtySinceSubmit = true`),使下一次送出必然產生新 key。否則使用者照文案「重新送出」會用同一把 key 再吃一次 409,形成無出路的迴圈。

**`TRADE_CONFLICT` 的相反處理:** 保留同一 key。它代表「這次沒寫入,但意圖沒變」,沿用同一 key 重送是安全且正確的(這也是文案敢寫「不會建立重複交易」的前提)。

| code | key 處置 | 文案的下一步 |
|------|---------|-------------|
| `TRADE_CONFLICT` | **保留** | 直接再送出一次 |
| 網路失敗 / 5xx | **保留** | 直接再送出一次 |
| `TRADE_IDEMPOTENCY_KEY_REUSED` | **丟棄** | 確認欄位後重新送出 |
| `VALIDATION_FAILED` / `fields` 類 | 保留(使用者一改欄位就會自動丟棄) | 修正欄位 |
| `TRADE_INSUFFICIENT_HOLDING` | 保留(同上) | 調整數量 |
| CSRF / 403 | 保留 | 重新整理 / 換帳號 |

### 8. Post-trade refetch 的畫面契約(SC 4 / D-10 / D-12)

**決定:保留舊值 + 區塊級 refresh 指示。不做全域 skeleton,不做全頁遮罩,不清空已渲染的資料。**

Phase 3 的區塊狀態機是 `loading | loaded | error`(`Positions.vue:323-331`),其中 `loading` 會把表格換成骨架列。**refetch 不得重用 `loading`** —— 交易剛成功卻讓整頁資料消失再長回來,是「看起來像出錯了」的典型誤導,也會讓 D-12 想避免的「以為交易沒成功」重演。

因此新增一個與 `loading` 並存的 `refreshing` 布林:

| 情境 | 狀態 | 視覺 |
|------|------|------|
| 首次載入(頁面 mount) | `status: 'loading'` | 既有骨架列 + `載入中…`(不改) |
| revision 觸發的重讀 | `status: 'loaded'` + `refreshing: true` | **保留現有資料**;區塊套 `.block-refreshing { opacity: .72; transition: opacity .15s }`;區塊頂部一條 12px `--fg-dim` 文字 `更新中…`;`aria-busy="true"` |
| 重讀成功 | `refreshing: false` | 恢復 opacity;新值直接替換(不做逐格動畫,避免與 `fresh` 高亮打架) |
| 重讀失敗 | `refreshing: false` + 額外 `refreshError` | **舊資料留在畫面上**,上方插入一條 `portfolioStaleAfterTrade` 說明 + 診斷列(code/traceId)+ `authRetry` 按鈕。**不進 `status: 'error'`**(那會清掉舊資料) |

**部分失敗的呈現(D-12 的核心):**

- 四個資料源(Overview summary、Overview recent trades、Positions summary+holdings、Trades 列表)**各自獨立**。一個失敗不影響其他三個的新資料。
- ticket 的 `result` 畫面**不受任何 refetch 結果影響** —— 它只渲染 `TradeDto`,不得因 refetch 失敗而改成錯誤畫面、被關閉、或加上警示。這是 D-12 明文要防的最糟失敗模式。
- refetch 失敗**不觸發** toast,也**不進** `SessionBanner`(除非是 401,那由 apiClient 的 session 路徑處理)。
- **mock mode 不得因 revision 變動而發出任何網路請求**(Pitfall 12);三個頁面的 watch 都要有 `if (live) return;` 的早退,並有測試鎖住。

**Trades 頁的重讀規則(D-11):** 走既有的單一入口 `applyQueryChange(() => {})`(`Trades.vue:281-286`)—— 保留篩選、保留排序、頁碼重置為 0、重新請求。**不得**複製 `pageNo.value = 0; void loadTrades();` 另造第二條重置邏輯。

**「不在目前檢視範圍」提示(D-11):** 重讀完成後比對 `lastCreatedTradeId` 是否出現在回傳 `items` 中;不在就在列表上方顯示 `tradeNotInCurrentView`(`--fg-dim` 12px 說明列,**不是**錯誤色 —— 這不是錯誤)。判定**只能比 id**,不得在前端重算「這筆是否符合當前篩選」(那是複製後端邏輯,踩 Phase 3 D-04 / judgment §7)。提示的清除時機:使用者變更任何篩選/排序/頁碼,或再次開啟 ticket。

### 9. 剛成交列的 `fresh` 高亮(D-13 / DP-10)

Phase 3 留下的兩條 TODO 註解必須清掉:`Positions.vue:264`、`Trades.vue:109`。DP-10 已裁定:Phase 3 的測試 `587e84e test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮` **明確反轉**(改寫斷言 + 更新測試名 + SUMMARY 交代),不可默默刪除。

| 契約項 | 值 |
|--------|-----|
| 高亮什麼 | Positions:`symbol` 等於 lastFill 的那一列。Trades:第 0 列且 `symbol` 相符(沿用既有綁定表達式 `Trades.vue:94`) |
| 動畫 | 既有 `tbody tr.fresh { animation: highlight 1.6s ease-out; }`(`Trades.vue:462-465` / `Positions.vue:762`),底色 `color-mix(in oklch, var(--accent) 22%, transparent)` → transparent。**不新增動畫** |
| 持續多久 | 動畫 1.6s 播一次;**`新` 標記在 `effectiveLastFill` 仍匹配期間常駐** |
| 如何淡出 | 動畫自身的 `ease-out` 到透明。標記的清除時機:再次開啟 ticket、Trades 頁任何篩選/排序/頁碼變更、或頁面 unmount(`App.vue:36` 的 `v-if` 切頁本來就會卸載)。**不用 setTimeout** —— 計時器會讓元件測試變得時間相依而 flaky,而 unmount 已經界定了實際壽命 |
| **a11y:不只靠顏色** | 該列的 symbol 欄位旁加一個 12px `.pill` 文字標記 `新` / `New`(`Trades.vue:470-473` 已有 `.pill` 樣式可複用),並給該列 `aria-label` 或 `<td>` 內的可讀文字。色盲、高對比模式、或動畫已結束的使用者都必須能看出是哪一列 |
| `prefers-reduced-motion` | `@media (prefers-reduced-motion: reduce)` 下取消 `highlight` 動畫,`新` 標記照常顯示 |
| 來源切換 | `effectiveLastFill = live ? live.lastFill : apiLastFill.value`;`LastFill` 形狀與 mock 的 `{ sym, type, qty, px }` 逐字一致,**綁定表達式不改,只換來源** |

### 10. mock / API mode 差異矩陣

| 元素 | mock mode | API mode |
|------|-----------|----------|
| 標的來源 | `createMockMarketApi()` 由 `data.ts` 組 `AssetDto` 形狀 | `GET /api/v1/assets`(分頁) |
| 走勢圖 | mock adapter 用 `genSeries()` 組 `KlineDto[]` | `GET /market/{symbol}/klines` |
| 訂單類型 / TIF / 交易後現金 | **保留**(D-04) | **隱藏**(不留空版位) |
| 手續費 | 使用者輸入,預設 0(D-02 兩模式一致) | 同 |
| 成交時間 | 使用者輸入,預設現在(兩模式一致) | 同 |
| routing/match 三階段 | **移除**(D-09 未區分模式 + judgment §1) | 移除 |
| 隨機成交價 / 亂數訂單號 | **移除** | 移除 |
| 通知推送 | 由 `createMockTradingApi()` 負責 | **不推**(notifications 屬 PORT-06 v2) |
| revision 觸發重讀 | **不發網路請求**(早退) | 三頁重讀 |
| `fresh` 高亮 | `live.lastFill` | `apiLastFill`(D-13) |

**元件不得 `import` 任何 mock store**(judgment §3)。`OrderTicket.vue:201-202` 的兩個 import 必須移除,改走 `getRuntimeApiClients()`。

---

## Layout Contract

| Surface | Layout requirement |
|---------|--------------------|
| Ticket 面板 | `width: 720px; max-width: 92vw`(既有);`border-radius: 14px`;`1px solid var(--border)`;`var(--surface)` 底 |
| Ticket 遮罩 | `rgba(0,0,0,0.42)` + `backdrop-filter: blur(6px)`;`z-index: 200`(既有);`Teleport to body` |
| Ticket header | 高度隨內容,`16px 24px` 內距,底部 `1px` 邊框;左:3 顆 step dot + 標題;右:`✕`(觸控目標 44px) |
| Ticket body(step 1) | `two-col` grid `1fr 1fr`,`gap: 24px`;**`< 760px` 改單欄**,右欄(報價卡+摘要)排在表單**下方** |
| Ticket body(step 2/3) | 單欄,`32px` 內距,置中 |
| 報價卡 | `var(--surface2)` 底 + `1px` 邊框 + `10px` 圓角 + `16px` 內距;走勢圖區固定 `96px` 高 |
| Symbol 下拉 | 絕對定位於輸入框下 `4px`,`max-height: 280px; overflow: auto`,`z-index: 10`(既有) |
| 底部錯誤區 | ticket body 內、footer 之上,全寬,`8px` 圓角;長 traceId 換行不撐破面板 |
| Footer | `16px 24px`,`var(--surface2)` 底,頂部 `1px` 邊框,左 ghost / 右 primary |
| 頁面區塊 refresh 指示 | 在既有 `.card` 內部頂端,不新增卡片、不改 grid span、**不得造成版面高度跳動** |
| `新` 標記 | 表格列內 inline pill,不改列高 |

**禁止:** hero 區、漸層背景、裝飾插圖、行銷文案、卡片內嵌卡片、ticket 內第二層 modal。這是操作型交易工具。

**Responsive:**

- `< 760px`:ticket 單欄;送出鈕與取消鈕維持並排(不堆疊),兩者都 `min-height: 44px`。
- 診斷列在 320px 寬度下必須換行完整顯示 code 與 traceId,不得水平滾動。
- zh 與 en 文案都不得讓按鈕文字溢出;`記錄中…` / `Recording…` 與 `記錄交易` / `Record trade` 的最寬者決定送出鈕 `min-width`。

---

## Accessibility Contract

**表單語意(現況有實質缺口,必須修):**

- 現行 `OrderTicket.vue` 的 `<label class="lab">`(`:18,53,63,71,75,86`)既沒有 `for`,也沒有包住 input —— **標籤與控件在程式上沒有關聯**。重建時全部改為 `for` + `id`(如 `trade-qty` / `trade-price` / `trade-fee` / `trade-executed-at` / `trade-note` / `trade-symbol`)或以 `<label>` 包裹。
- 數量、價格、手續費:`type="number"` + `inputmode="decimal"`;`aria-describedby` 指向該欄的說明文字(`tradeFeeHint` / `tradeExecutedAtHint`)與錯誤節點,多個 id 以空白分隔。
- 欄位錯誤:節點 id 為 `trade-{field}-error`,對應輸入框 `aria-invalid="true"` 且 `aria-describedby` 含該 id。**欄位錯誤節點不加 `role="alert"`**(6 個同時觸發會連續朗讀 6 次);由 `aria-describedby` 在聚焦時朗讀。
- Ticket 底部錯誤區:`role="alert"`(一次只有一條,適合立即播報)。
- Symbol combobox:輸入框 `role="combobox"` + `aria-expanded` + `aria-controls` + `aria-autocomplete="list"` + `aria-activedescendant`;下拉 `role="listbox"`,每列 `role="option"` + `aria-selected`。**必須支援鍵盤**:`↑`/`↓` 移動、`Enter` 選取、`Esc` 關閉。現況只綁了 `@mousedown`(`:37`),鍵盤使用者無法選標的。

**送出與狀態播報:**

- 送出中:ticket 容器 `aria-busy="true"`;一個 `role="status" aria-live="polite"` 節點文字 `記錄中…`。
- 送出成功:焦點移到 result 標題(`<h2 tabindex="-1">`,沿用 `AuthPanel.vue:27` 的既有做法);標題本身即成功播報,`aria-live` 不重複播。
- 區塊 refetch:`aria-busy="true"` + `更新中…` 可見文字(`role="status"` 用 `polite`,不打斷使用者)。
- refetch 失敗:`portfolioStaleAfterTrade` 節點 `role="status"`(**不用 `alert`** —— 交易已成功,這不是需要打斷的錯誤)。
- 提交錯誤:`role="alert"`(需要打斷)。

**其他:**

- Loading 一律有文字,不得只有 spinner / 骨架(沿用 `01-UI-SPEC.md:183`、`02-UI-SPEC.md:196`)。
- 焦點可見:輸入框、side toggle、下拉選項、footer 按鈕、重試鈕、`✕` 全部要有可見 focus ring(`.inp:focus` 已有 accent 邊框;按鈕需補)。
- 錯誤不得只靠顏色:每一條錯誤都有文字;`aria-invalid` 提供程式化訊號。
- BUY/SELL 不得只靠顏色:文字 + `↗`/`↘` 字符必須保留。
- `fresh` 列不得只靠顏色與動畫:見 §9 的 `新` 標記。
- `prefers-reduced-motion: reduce` 下停用:`fresh` 高亮動畫、`.check` 的 `pop` 彈入、ticket 的 `rise`/`fade`、skeleton 的 `skeletonPulse`(改為靜態底色)。

---

## Security & Diagnostics Display Contract

**UI 絕不顯示或持久化:**

- access token / refresh token 值、`stock_access` / `stock_refresh` cookie 值、raw `Set-Cookie`。
- Redis key、內部 session id、refresh token hash。
- 後端 `error.message` 原文(只用 `error.code` 分派到前端自有文案)。
- `error.fields` 的 value(英文 Bean Validation 訊息,D-16 明文禁止;必須有測試斷言這些字串**不出現在 DOM**)。
- **idempotency key**。它是請求層的實作細節,顯示它只會邀請使用者手動改動;而後端錯誤訊息也不得回射它(`code-standards.md:79-84`)。

**UI 可以顯示:**

- 後端 `error.code`(僅在錯誤狀態)。
- `meta.traceId` / `ApiClientError.requestId`(僅在錯誤狀態,Phase 3 D-12)。
- HTTP status(可選,與 code 同列)。
- `TradeDto.id`(UUID,除錯回報用途,D-09 明文)。
- `AssetDto` 的公開行情欄位。

---

## Test-Visible Contract(`data-testid` 清單)

沿用 Phase 2/3 的 kebab-case 慣例(`positions-summary-error` / `trades-retry` / `auth-login-submit`)。planner 應在計畫中把這些 testid 當成驗收接點。

| testid | 用途 |
|--------|------|
| `ticket-symbol-input` / `ticket-symbol-options` / `ticket-symbol-option-{SYM}` | typeahead |
| `ticket-symbol-loading` / `-empty` / `-no-tradable` / `-error` / `-error-code` / `-trace-id` / `-retry` / `-truncated` | typeahead 各態 |
| `ticket-quote-chart-loading` / `-empty` / `-error` / `-retry` | 走勢圖三態 |
| `ticket-qty` / `ticket-price` / `ticket-fee` / `ticket-executed-at` / `ticket-note` | 表單欄位 |
| `ticket-field-error-{field}` | 欄位級錯誤(field ∈ symbol/type/quantity/price/fee/note/executedAt) |
| `ticket-sellable-qty` / `ticket-sellable-loading` / `ticket-sellable-failed` | D-15 |
| `ticket-review-advance` / `ticket-submit` / `ticket-back-to-edit` | footer 動作 |
| `ticket-submitting-status` | `role="status"` 送出中播報 |
| `ticket-error` / `ticket-error-code` / `ticket-error-trace-id` | 底部錯誤與診斷 |
| `ticket-result` / `ticket-result-trade-id` / `ticket-result-price` / `ticket-result-executed-at` | result 畫面 |
| `{page}-refreshing` / `{page}-refresh-error` / `{page}-refresh-error-code` / `{page}-refresh-trace-id` / `{page}-refresh-retry` | 三頁 refetch(page ∈ overview/positions/trades,沿用既有前綴慣例) |
| `trades-not-in-current-view` | D-11 提示 |
| `positions-fresh-badge` / `trades-fresh-badge` | D-13 非顏色線索 |

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | none | not required — `components.json` 不存在(`ls` 實跑驗證),shadcn 未初始化且未被 Phase 1/2 授權 |
| third-party registries | none | 未宣告任何第三方 registry;`npx shadcn view` 貼源審查因此**不適用**(N/A — no third-party block declared, 2026-07-26) |

本階段**不引入任何新的第三方套件、icon 套件、component block 或 UI registry**(`04-RESEARCH.md` §Standard Stack 明文:「本階段不引入任何新的第三方套件」;§Package Legitimacy Audit 為零新增依賴)。debounce 自行以 `setTimeout`/`clearTimeout` 實作,**不得**為此引入 lodash 或類似套件(DP-12 + Package Legitimacy Audit)。若後續 plan 提出任何 registry/套件,必須先跑獨立的 package legitimacy + registry safety 審查。

---

## Decisions Made By This Spec(我的裁量與理由)

上游把這幾項交給我或未涵蓋,這裡逐條落定,供 plan review 時 Yuan 一眼看到:

| # | 決策 | 依據 / 理由 |
|---|------|-----------|
| U-01 | **保留 review 步驟**,step dots 4 → 3,送出中不切步驟 | DP-9 授權裁量;append-only 帳本需要確認關卡;review 是 `dirtySinceSubmit` 的自然邊界 |
| U-02 | 送出中**凍結整張 ticket**(欄位、返回、關閉、遮罩),送出鈕必須有明確 `:disabled` | Q6.2(現況 `:185` 無 `:disabled`,只靠「按鈕從 DOM 消失」)+ TRAD-04;凍結消除 D-14 規則 2 在 in-flight 期間的競態 |
| U-03 | 冪等命中**顯示與首次建立完全相同的成功畫面**,不做「已存在」變體 | 現行契約沒有 replay 訊號;加訊號是 API shape 變更(judgment §9 需先問)。planner 不得自行加 |
| U-04 | `TRADE_IDEMPOTENCY_KEY_REUSED` 必須**丟棄 key**;`TRADE_CONFLICT` 與網路失敗必須**保留 key** | 不丟棄會讓使用者照文案重送再吃 409,形成無出路迴圈;保留才讓「重試不會建立重複交易」的文案成立 |
| U-05 | refetch 用**新的 `refreshing` 旗標**保留舊值,不重用 Phase 3 的 `loading`(會清空表格) | D-12:交易後畫面「看起來像出錯」是最危險的失敗模式 |
| U-06 | refetch 失敗**保留舊資料 + 明示「可能不是最新」**,不進 `status:'error'` | D-12「兩件事分開呈現」;但必須明確標示,避免把 stale 值當後端真相呈現 |
| U-07 | 診斷資訊用**常駐低調單列**,不用 `<details>`,**不加複製按鈕** | Phase 2/3 已有一致慣例;複製按鈕是新機制(clipboard + 回饋 + 降級)且 code/traceId 可直接選取 |
| U-08 | CSRF 403 顯示在 **ticket 底部**(不是全域 banner) | D-16 是更晚更具體的決策,勝過 `02-UI-SPEC.md:144`;分界為「bootstrap 失敗 → banner,單一請求被拒 → 發起處」 |
| U-09 | typeahead **不做分頁/無限捲動**,改用「僅顯示前 10 筆」提示 | DP-5 帶來的分頁態需求;combobox 內分頁是新機制且無前例,縮小關鍵字更便宜 |
| U-10 | `tradeable === false` 全被過濾時,用**專屬空狀態文案**(與「查無結果」分開) | 否則使用者以為自己打錯字 |
| U-11 | 走勢圖 loading/empty/error **一律不阻擋送出**;需有測試鎖住 | Q6.5;走勢圖是輔助資訊,不是交易前提 |
| U-12 | `fresh` 高亮加**文字 `新` pill**,並在 `prefers-reduced-motion` 下保留 pill、停用動畫;**不用計時器**清除 | D-13 + a11y「不只靠顏色」;計時器讓測試時間相依而 flaky,`v-if` 切頁卸載已界定壽命 |
| U-13 | Primary CTA 定為 **`記錄交易` / `Record trade`**,並禁用既有 `placeOrder`/`filled`/`avgFillPx`/`routingMatch`/`placing` 五個 i18n key | judgment §1(不得出現下單/成交/撮合語意)+ D-09 |
| U-14 | 手續費與成交時間各給一條**常駐說明文字**(不是錯誤色) | D-02 的理由(fee 永久進 `avg_cost` 且改不回來)必須讓使用者知道,否則預設 0 只是換一種默默出錯 |
| U-15 | 字級收斂為 12/13/16/20 四級、字重 400/600 兩種,並列出 7 處必改的既有宣告 | 模板要求 3-4 級 / 2 重;`OrderTicket.vue` 現有 10/11/13/15/22/24/28 共 7 級 |
| U-16 | routing/match、隨機 slippage、亂數訂單號在 **mock mode 也移除**(D-04 的四樣則 mock 保留) | D-09 未區分模式(對比 D-04 明文「mock 保留」),且 judgment §1 明文點名 routing |

**明確不在本契約範圍(不要在 Phase 4 設計它):**

- `executedAt` 未來時間的**後端**驗證 —— DP-1 已由 Yuan 裁定為 (c),留給 PR #15。前端仍做 `max` 屬性 + 送出前自檢 + 底部 fallback 文案(Q8.4 建議兩者都做),但**不得**為此新增後端專屬錯誤態設計。
- 可用現金 / 今日損益 / 資產分類 / watchlist 的 UI —— Phase 04.1。
- 批次補登(CSV import)、多幣別呈現、notifications API 化 —— Deferred。
- pending order / cancel / partial fill / TIF 的任何 UI 或文案 —— v2(TRAD-07~09)。

---

## Checker Sign-Off

- [ ] Dimension 1 Copywriting: PASS
- [ ] Dimension 2 Visuals: PASS
- [ ] Dimension 3 Color: PASS
- [ ] Dimension 4 Typography: PASS
- [ ] Dimension 5 Spacing: PASS
- [ ] Dimension 6 Registry Safety: PASS

**Approval:** pending

---

*Phase: 4-manual-trade-creation-idempotency-post-trade-refetch*
*UI spec drafted: 2026-07-26*
