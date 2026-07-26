# Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-26
**Phase:** 4-manual-trade-creation-idempotency-post-trade-refetch
**Areas discussed:** Ticket 欄位與資料來源, Idempotency 契約細節, Post-trade refetch 範圍, 錯誤與重試 UX(Yuan 全選四塊)

---

## 前置:pending todo 排程

| Option | Description | Selected |
|--------|-------------|----------|
| 留 backlog,不進 Phase 4 | 保持 Phase 4 為垂直切片;milestone 收尾再統一決定進 v1.1 | |
| 資產分類現在就做 | 只是 JOIN + DTO 欄位,但受益者是 Phase 3 的頁面 | |
| 現在插一個新 phase | 用 `/gsd-phase` 在 4 與 5 之間插入「後端資料缺口補齊」phase | ✓ |

**User's choice:** 現在插一個新 phase
**Notes:** Yuan 先反問「甚麼時候要補上? 後端有嗎」。查證後回報四條的真實後端現況,其中**資產分類那條的 todo 描述被證明過度悲觀**(`assets.sector` / `asset_type` 欄位存在且 V2 seed 有值,`AssetDto` 已回傳 sector,缺的只是 portfolio SQL 沒 JOIN)。Yuan 據此決定插新 phase 而非留 backlog。新 phase 排在 Phase 4 之後,所以 Phase 4 進行期間「可用現金」仍無來源,`cashAfter` 照 D-16 隱藏。

---

## Ticket 欄位與資料來源

### 1/4 symbol 選單與報價卡資料來源

| Option | Description | Selected |
|--------|-------------|----------|
| /assets 搜尋 + 真實報價數字,走勢圖隱藏 | 報價卡數字用 AssetDto 真值;`genSeries()` 走勢圖隱藏(接 klines 屬額外範圍) | |
| /assets 搜尋 + 報價卡連走勢圖一起接真資料 | 走勢圖也接 `GET /api/v1/market/{symbol}/klines` | ✓ |
| /assets 搜尋 + 整張報價卡隱藏 | 只做 symbol 驗證,價格全手動輸入 | |
| 保留本地 data.ts 清單 | 改動最小,但 API mode 仍讀 mock 資料(違 judgment §3) | |

**User's choice:** 報價卡連走勢圖一起接真資料
**Notes:** 討論中提出的關鍵事實是 `AssetDto` 單一回應已含 symbol/name/sector/latestPrice/change/changePercent/volumeText/high/low,選單與報價卡不需兩個端點。本選擇使 Phase 4 成為 klines 端點的第一個前端消費者(Chart 頁仍未 API 化),mapping 與錯誤態無前例可抄。

### 2/4 手續費 fee

| Option | Description | Selected |
|--------|-------------|----------|
| 使用者手動輸入,預設 0 | fee 會進 `HoldingCalculator` 算入 avg_cost / realized_pnl,而 transactions 是 append-only 改不回來 | ✓ |
| 保留 0.1% 估算當預設值,可編輯 | 方便,但預設值是前端發明的費率,使用者很可能直接送出 | |
| 固定送 0 且不顯示 fee | 不污染資料,但真實手續費無法記錄 | |

**User's choice:** 手動輸入,預設 0

### 3/4 是否給 executedAt 補登欄位

| Option | Description | Selected |
|--------|-------------|----------|
| 不給欄位,一律用現在 | ticket 語意是「我剛成交」;範圍最小 | |
| 給日期時間欄位,預設現在 | 手動帳本的真實需求;代價是驗證(不可未來、時區)與篩選互動 | ✓ |

**User's choice:** 給日期時間欄位,預設現在
**Notes:** 此選擇產生兩個下游後果,均已寫入 CONTEXT:(a) 補登交易可能不在 Trades 頁當前篩選/排序/頁碼內 → 催生 D-11 的「不符則明說」;(b) **正面副作用** —— executedAt 由前端明確送出,使得 D-07 的 payload 比對可以直接比已存交易列,不需額外 fingerprint 欄位。

### 4/4 送出中與成功畫面

| Option | Description | Selected |
|--------|-------------|----------|
| 收成「送出中 → 已記錄」兩態 | 移除三階段假進度、slippage、亂數 orderId;成功頁顯示後端 TradeDto | ✓ |
| 保留四步 wizard 外觀,只改文案 | 視覺與 mock 一致;但三階段進度條本身仍在暗示 broker 撮合 | |
| 直接關閉 ticket + toast | 最少 UI;但看不到 trade id,出錯無可回報的識別碼 | |

**User's choice:** 收成兩態

---

## Idempotency 契約細節

> 開場說明:`judgment.md` §5 已鎖定「唯一約束 + duplicate 回既有交易 + 前端 guard 只是 UX」,故只討論 §9 要求先問的契約 shape 三項。

### 1/3 Idempotency-Key 必填還是選填

| Option | Description | Selected |
|--------|-------------|----------|
| 必填,缺少回 400 | 選填等於防護可被繞過;目前無真實 client,遷移成本只有後端測試 | ✓ |
| 選填,缺少則維持現行行為 | 向後相容、與 BackfillController 一致;但保證變成「只有帶 key 時才成立」 | |
| cookie 請求必填、bearer 選填 | 兩邊兼顧;但同一端點兩種驗證規則,語意詭異且測試矩陣變兩倍 | |

**User's choice:** 必填,缺少就 400

### 2/3 同一 key 送不同 payload

| Option | Description | Selected |
|--------|-------------|----------|
| 回 409 + 專用 error code | 能暴露前端 key 管理 bug;比對成本幾乎為零 | ✓ |
| 一律回既有交易,忽略新 payload | 最貼近 §5 字面;但使用者可能認為送出的是新那一筆 | |
| 回既有交易但記 WARN log | 零額外欄位;問題只存在 log 裡 | |

**User's choice:** 409 + 專用 error code(建議名 `TRADE_IDEMPOTENCY_KEY_REUSED`)
**Notes:** 此選擇與後續 D-14(key 生命週期)存在互鎖風險,在「錯誤與重試」第 2 題明確處理。

### 3/3 key 存放位置

| Option | Description | Selected |
|--------|-------------|----------|
| transactions 新增欄位 + 唯一約束,永久保留 | 零額外表零 join;不存在「過期後同 key 又能建重複」的窗口 | ✓ |
| 獨立 trade_idempotency 表 + 定期清理 | 主表不動、可設保留期;但清理後同 key 重送會建出重複交易 | |
| Redis SET NX EX(照 BackfillIdempotencyService) | 不用 migration;但 Redis 非唯一約束(§5 明文要求),且與 DB transaction 非原子 | |

**User's choice:** transactions 新增欄位 + partial unique index,永久保留
**Notes:** 討論中確認 `ALTER TABLE ADD COLUMN` 不受 V8 append-only trigger 影響(trigger 只擋 row 層 UPDATE/DELETE/TRUNCATE),且 `insertTransaction` 已是 `insert ... returning`,好接 `on conflict`。

---

## Post-trade refetch 範圍

### 1/4 重讀範圍與觸發

| Option | Description | Selected |
|--------|-------------|----------|
| shared revision counter,已掛載的頁自行重讀 | 沿用各頁現有 load 函式;沒掛載的頁不做事(mount 時本來就重抓) | ✓ |
| 一律打三個 domain 請求 | 直覺上最完整;但無 client-side cache,沒掛載的頁沒人消費 → 無效工 | |
| 成功後強制導航到 Positions | 最省程式(mock 現在就這樣);但強抽離使用者的工作情境 | |

**User's choice:** shared revision counter
**Notes:** 決策依據是查證出的架構事實:`App.vue:36` 用 `v-if` 切頁,非當前頁是卸載的;OrderTicket 是全域 overlay,使用者可能在 Markets/Chart 頁下單。

### 2/4 Trades 頁的篩選/排序/頁碼

| Option | Description | Selected |
|--------|-------------|----------|
| 保留篩選排序、頁碼回 0、不符則明說 | 與 Phase 3 D-15 同一邏輯;補登交易可能不在結果集內,明講勝過讓使用者困惑 | ✓ |
| 篩選與頁碼原封不動重讀 | 最不打擾;但在第 3 頁下單後看不到新交易,易誤判失敗而再送一次 | |
| 清掉篩選、回預設排序與第 0 頁 | 多數情況保證看得到;但丟掉篩選狀態,且補登到去年的交易依然不在第 0 頁 | |

**User's choice:** 保留篩選排序、頁碼回 0、不符則明說

### 3/4 交易成功但 refetch 失敗

| Option | Description | Selected |
|--------|-------------|----------|
| 成功訊息與 refetch 失敗分開呈現 | 使用者絕不會把「畫面沒更新」誤讀為「交易沒成功」 | ✓ |
| 整個流程視為失敗顯示錯誤 | 最危險:交易已入帳,使用者不知道所以再送一次 | |
| 靜默自動重試幾次再放棄 | 多數瞬間性故障自動修好;但最終失敗仍要落到第一項,算加強版 | |

**User's choice:** 分開呈現

### 4/4 API mode 的 fresh 高亮

| Option | Description | Selected |
|--------|-------------|----------|
| 接上 | 用回傳的 TradeDto 產生 lastFill 等價物;兩模式視覺一致,並清掉 Phase 3 的 TODO 註解 | ✓ |
| 不接 | 少一份狀態;但 Phase 3 留的 TODO 註解會繼續誤導後人 | |

**User's choice:** 接上

---

## 錯誤與重試 UX

### 1/3 oversell 是否前端預檢

| Option | Description | Selected |
|--------|-------------|----------|
| SELL 時載入該標的持倉作預檢並顯示可賣數量 | 「可賣 N 股」是 mock 也沒有的真實資訊;後端 409 仍是最終權威 | ✓ |
| 純靠後端 409,前端不預檢 | 完全不平行實作業務規則、最少請求;但填完整張表才被拒 | |
| 只在已載入 holdings 時才預檢 | 不多打請求;但同一操作在不同頁行為不同,比全部不預檢更難解釋 | |

**User's choice:** SELL 時載入持倉預檢並顯示可賣數量
**Notes:** 明確界定預檢只比數量上限、不重算成本或損益,故不牴觸 Phase 3 D-04。

### 2/3 Idempotency key 何時換新

| Option | Description | Selected |
|--------|-------------|----------|
| 按下送出時產 key;該次嘗試的重試沿用;回表改過欄位就換新 | 重試沿用符 §5、改動即新意圖,且避開與 D-07 的 409 互鎖 | ✓ |
| 一張 ticket 一個 key,成功或按「新訂單」才換 | 規則更簡單;但驗證失敗後改數量再送會吃 409 KEY_REUSED,使用者卡死 | |
| 只有網路類錯誤沿用,後端回 4xx 就換新 | 語意最細;但前端要分類錯誤來決定換不換 key,邏輯錯了很難測 | |

**User's choice:** 按下送出時產 key,重試沿用,改動欄位換新
**Notes:** 「一張 ticket 一個 key」與前面 Idempotency 2/3 的 409 決策存在真實死鎖(不是理論風險),已要求測試明確覆蓋「400 → 改欄位 → 再送 → 應成功」這條路徑。

### 3/3 錯誤顯示位置

| Option | Description | Selected |
|--------|-------------|----------|
| 欄位級綁欄位 + 其餘在 ticket 底部 | 後端確實回 `ApiError.fields`;底部錯誤帶 error code + traceId | ✓ |
| 全部集中在 ticket 底部一條 | 最簡單;但使用者要自己找哪一欄填錯,而後端已把資訊送來卻不用 | |
| 全部走 Toast | 實作最少;但 Toast 會自動消失,帶 traceId 的錯誤不該用不可回顧的元件 | |

**User's choice:** 欄位級綁欄位 + 其餘在底部
**Notes:** 提問前先查證了 `GlobalExceptionHandler:56-64` 確實把 `@Valid` binding 錯誤填進 `ApiError.fields`(Phase 3 記憶中的「後端不送 error.field/details」指的是前端草案信封的不同鍵名,不是這個 `fields` map)。同時界定 `fields` 的 value 是 Bean Validation 英文預設訊息,不得直接顯示,前端須依 field 名稱對應自己的 i18n。

---

## Claude's Discretion

本次 Yuan 對所有提問都給了明確選擇,無「你決定」項目。以下由我依既有規範裁決並附依據(詳見 CONTEXT.md):

- trading adapter 獨立成 `tradingApi.ts` 而非併進 `portfolioApi.ts` —— 依據 REQUIREMENTS.md **VER-02** 的字面(「portfolio adapters、trading adapter」列為不同 adapter)。
- 隱藏 MKT/LMT 與 TIF、移除 slippage 與亂數 orderId —— judgment §1 反例明文點名,無討論空間。
- 隱藏 `cashAfter` —— 後端零命中,Phase 3 D-14/D-16 原則。
- price 預填 `AssetDto.latestPrice` 但可編輯 —— MKT 段控隱藏後的必然結果,且符合「手動記錄已成交價格」語意。
- `OrderTicket.vue` 移除 mock store 直接 import —— judgment §3。
- key 用 `crypto.randomUUID()`;前端 duplicate-submit guard 沿用現有 `placing` ref。
- 元件拆分、loading 骨架、日期選擇器版面、「不在篩選條件內」的文案 → 依現有 UI 慣例。

## Deferred Ideas

- **四條後端資料缺口** → Yuan 決定用 `/gsd-phase` 在 Phase 4 之後插入新 phase(含資產分類 todo 描述的更正)。
- **批次補登歷史交易(bulk import)** — 與 D-03 的單筆日期欄位是不同使用情境。
- **notifications API 化** — OrderTicket 的通知推送在 API mode 不做,屬 PORT-06(v2)。
- **多幣別呈現** — `assets.currency` 有值但 portfolio 彙總不分幣別;交易建立會讓問題更明顯,但修正需先定義換匯來源。Phase 3 討論尾聲已列為可選項目,仍未展開。
