# Trading & Portfolio API Contract

> 後端是權威（judgment §4）。本檔記錄 `stock-module-trading` 對前端公開的契約：端點、DTO 欄位、驗證規則、錯誤碼與序列化格式。
> 認證、CSRF、401/403 信封見 [browser-auth-contract.md](browser-auth-contract.md)；本檔只談交易與持倉本身。
>
> 程式碼是事實來源。與本檔衝突時以程式碼為準，並回頭修本檔。
> 權威檔案：`TradingController`、`CreateTradeRequest`、`TradeDto`、`HoldingDto`、`PortfolioSummaryDto`、`TradingService`、`PortfolioQueryService`、
> `CashLedgerController`、`CreateCashEntryRequest`、`CashEntryDto`、`CashBalanceDto`、`AllocationSliceDto`、`SectorAllocationDto`、`CashLedgerService`、`ErrorCode`；
> Phase 04.2 起另含 `CashDividendController`、`CreateDividendRequest`、`DividendDto`、`DividendService`、`ShareAdjustmentController`、`CreateShareAdjustmentRequest`、`ShareAdjustmentDto`、`ShareAdjustmentService`、`PortfolioActivityController`、`PortfolioActivityDto`、`ActivityQueryService`。
> 觀察清單（watchlist，asset 模組）的契約在 [watchlist-contract.md](watchlist-contract.md)。
>
> ℹ️ **〔04.2〕段落已實作**：本檔標 〔04.2〕 的段落（股利 / 股數調整 / 活動列表與 summary 新欄位）於 2026-11-01（04.2-02，Yuan 於決策閘門裁決 option-recommended）凍結為先行契約；04.2-03 ~ 04.2-10 已合入（後端 PR #51、前端 PR #19），目前**程式碼是事實來源**——程式與本檔偏離時以程式為準，回頭修本檔並回報。
> 使用者基準幣（`GET /api/v1/me` 的 `baseCurrency`、`PATCH /api/v1/me/preferences`）的契約在 [browser-auth-contract.md](browser-auth-contract.md#user-preferences)。

## 共通

- 所有回應都包在 `ApiResponse<T>`：`{ success, data, error, meta }`，`meta.traceId` 恆存在。
- 列表回應的 `data` 是 `PageResponse<T>`：`{ items, page, size, totalElements, totalPages }`，`page` 從 0 起算。
- 所有端點都需登入；權限見各端點。
- 一般 API 限流：已登入每位使用者 100 次 / 分鐘，超過回 429 `AUTH_RATE_LIMITED` 並帶 `Retry-After`（security.md §15）。

## 序列化格式（前端最容易踩錯的地方）

| 型別 | JSON 形式 | 備註 |
|---|---|---|
| 金額與數量（Java `BigDecimal`） | **JSON number**，例如 `100.50000000` | 資料庫為 `NUMERIC(24,8)`，最多 8 位小數。前端以 `number` 接收；值域上限（quantity ≤ 10⁹、price ≤ 10⁶）確保在 double 精度內可表示，但**不要在前端做累加後再比較相等**。 |
| 時間（`OffsetDateTime`） | ISO-8601 字串，帶偏移量 | 伺服器時區為 `Asia/Taipei`，輸出多為 `+08:00`；前端一律以「帶偏移量的 instant」處理，不要假設偏移。 |
| id | 字串 UUID | 對外一律 UUID，內部 Long id 不外露（例外：`/me` 的 `id`，見 browser-auth-contract）。 |

> 金額欄位若值為 0 且 scale 為 8（例如無任何現金紀錄的 `cash`），`BigDecimal` 的 JSON 形式可能是 `0E-8`。這是合法的 JSON number，請以數值解析，**不要用字串比對**。

> ⚠️ K 線與即時報價（`KlineDto`、`LatestPriceDto`、WS tick）的價格是**字串**，交易與持倉的金額是**數字**。兩者格式不同是既有現況（架構審查 M-7），前端各自的轉換點見 `marketApi.closeSeries` 與 `tradingApi`。

## POST /api/v1/trades — 記錄一筆已成交的交易

權限：`TRADE_EXECUTE`。語意是**已成交的手動紀錄**，不是委託單：沒有 pending、取消、有效期或撮合。

### Header

| 名稱 | 必填 | 規則 |
|---|---|---|
| `Idempotency-Key` | **是** | 1–128 字元，不得全空白。同一使用者、同一把鍵只會建立一筆交易。 |

冪等語意：

- 同一把鍵、**相同 payload** 重送 → 回傳**既有那筆交易**（200），不重複建立、不重複更新持倉。
- 同一把鍵、**不同 payload** → 409 `TRADE_IDEMPOTENCY_KEY_REUSED`。
- payload 比對欄位：`symbol`（解析後的標的）、`type`、`quantity`、`price`、`fee`、`executedAt`。**不含 `note`**。數值以大小比較（`100` 等於 `100.00`），時間截到微秒。
- 前端建議：使用者編輯表單後換一把新鍵；網路失敗後原樣重送沿用舊鍵。
- 缺 header 或空白／過長 → 400 `VALIDATION_FAILED`，`error.fields['Idempotency-Key']` 指名 header（可與 body 欄位錯誤區分）。

### Body — `CreateTradeRequest`

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `symbol` | string | 是 | 不得空白；須為存在且可交易的標的 |
| `type` | string | 是 | `BUY` 或 `SELL` |
| `quantity` | number | 是 | 0.00000001 ≤ x ≤ 1,000,000,000；最多 10 位整數、8 位小數 |
| `price` | number | 是 | 0.00000001 ≤ x ≤ 1,000,000；最多 7 位整數、8 位小數 |
| `fee` | number | 否 | ≥ 0；最多 16 位整數、8 位小數；省略視為 0 |
| `note` | string | 否 | 最多 500 字 |
| `executedAt` | string（ISO-8601） | 否 | 省略視為現在；不得晚於伺服器時間 5 分鐘以上（時鐘偏移容忍）；〔quick-261107-1j4〕不得早於 `1900-01-01T00:00:00Z`（含邊界、比較 instant；含帶號擴充年份如 `-10000-01-01T00:00:00Z`）→ 400 `fields.executedAt`（`must not be before 1900-01-01`）；〔04.2〕不得早於該標的最晚一筆未沖銷股數調整的生效起點（見〈錯誤〉） |

欄位驗證失敗 → 400 `VALIDATION_FAILED`，`error.fields` 以欄位名為鍵。

### 回應 — `TradeDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | 交易 UUID（36 字元，前端不得截斷） |
| `symbol` | string | |
| `type` | string | `BUY` / `SELL` |
| `quantity` | number | 後端實際記錄的值（顯示時以此為準，不用表單值） |
| `price` | number | 同上 |
| `fee` | number | 同上 |
| `note` | string \| null | |
| `executedAt` | string | |
| `createdAt` | string | |
| `currency` | string | 標的交易幣別（`USD` / `TWD` / `EUR` / `JPY`），經 `AssetFacade` 取得；`price` 與 `fee` 以此幣別計。`POST`（含冪等命中的 200）與 `GET` 列表每一列皆帶；資產屬性缺失（理論上不會發生）時為 `null` |

**不含 `idempotencyKey`**——那是請求層的實作細節。

### 錯誤

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | body 欄位或 `Idempotency-Key` 不合法；看 `error.fields` |
| 400 | `VALIDATION_FAILED` | body 不是合法 JSON 或欄位型別錯（2026-10-24 前為 500，D-27）；訊息固定、無 `error.fields` |
| 400 | `VALIDATION_FAILED` | `executedAt` 晚於伺服器時間 5 分鐘以上 → `error.fields.executedAt = "must not be in the future"`（Phase 5 D-02a；先前無 fields），與 1900 下限同形；在任何資產解析與寫入之前擋下，訊息不回射輸入。5 分鐘以內視為時鐘偏移，照常入帳 |
| 400 | `VALIDATION_FAILED` | 〔quick-261107-1j4〕`executedAt` 早於 `1900-01-01T00:00:00Z`（下限含邊界，比較 instant：台北本地 `1900-01-01T00:00+08:00` = `1899-12-31T16:00Z` 被拒）→ `error.fields.executedAt = "must not be before 1900-01-01"`；在任何資產解析與寫入之前擋下，訊息不回射輸入。先前 DB 存不下的極端值會在 insert 變成 500 |
| 400 | `VALIDATION_FAILED` | 〔04.2〕`executedAt` 早於該標的最晚一筆**未沖銷**股數調整的 `effective_at`（除權日交易日起點）→ `error.fields.executedAt`；交易與持倉皆不變。理由：持倉是物化狀態，拆股後補登拆股前的買入會以拆前股數加進拆後持倉（靜默錯帳）。沒有股數調整的標的行為與 Phase 4 相同。要補登更早的交易，先沖銷該調整、補登後再重記 |
| 400 | `TRADE_UNSUPPORTED_TYPE` | `type` 不是 BUY / SELL |
| 404 | `ASSET_NOT_FOUND` | 標的不存在或不可交易 |
| 409 | `TRADE_INSUFFICIENT_HOLDING` | SELL 數量超過持有 |
| 409 | `TRADE_IDEMPOTENCY_KEY_REUSED` | 同一把鍵搭配不同 payload |
| 409 | `TRADE_CONFLICT` | 併發下持倉同時被更動（極少見，可重送） |
| 403 | `AUTH_FORBIDDEN` | 缺 `TRADE_EXECUTE` 權限 |
| 429 | `AUTH_RATE_LIMITED` | 一般 API 限流 |

錯誤訊息不回射使用者輸入（包含冪等鍵）。

## GET /api/v1/trades — 交易紀錄

權限：`PORTFOLIO_VIEW`。

| 參數 | 預設 | 規則 |
|---|---|---|
| `symbol` | — | 篩選單一標的 |
| `type` | — | `BUY` / `SELL` |
| `dateFrom` | — | 成交時間下界（含） |
| `dateTo` | — | 成交時間上界（**不含**） |
| `sort` | `executedAt` | 白名單：`executedAt`、`createdAt`、`total`（quantity × price）、`quantity` |
| `direction` | `desc` | `asc` / `desc` |
| `page` | `0` | 夾限至 0..10000 |
| `size` | `20` | 夾限至 1..100 |

時間參數接受三種格式：

| 格式 | 解讀 |
|---|---|
| `2026-01-01T00:00:00+08:00` | 原樣 |
| `2026-01-01T00:00:00` | 補上 `+08:00` |
| `2026-01-01` | 整個當日：作為下界取當日 00:00，作為上界取**隔日** 00:00 |

`dateFrom` 晚於 `dateTo`、白名單外的 `sort`、無法解析的時間 → 400 `VALIDATION_FAILED`。
query string 中未編碼的 `+` 會被解成空白，後端會還原，但前端仍應正確編碼。

回應：`PageResponse<TradeDto>`。

## GET /api/v1/portfolio/holdings — 持倉

權限：`PORTFOLIO_VIEW`。回應：`HoldingDto[]`（只含持有數量大於 0 的標的）。

> 既有金額欄位（`avgCost`、`costBasis`、`marketPrice`、`marketValue`、`realizedPnl`、`unrealizedPnl`）為**原幣**，幣別見 `currency`。需要跨標的比較或計算權重時請用 `marketValueInBase`（已折算為使用者基準幣）；跨標的比較損益（例如 Top movers 排行）請用 `unrealizedPnlInBase`。今日欄位（`previousClose`、`dayPricePnl`、`dayPnlBasis`）同為原幣。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `assetId` | string | 標的 UUID |
| `symbol` | string | |
| `assetName` | string | |
| `totalQuantity` | number | |
| `avgCost` | number | 平均成本（原幣） |
| `costBasis` | number | totalQuantity × avgCost（原幣） |
| `marketPrice` | number | 估值用的最新價（見下；原幣） |
| `marketValue` | number | totalQuantity × marketPrice（原幣） |
| `realizedPnl` | number | 此標的的已實現損益（原幣） |
| `unrealizedPnl` | number | marketValue − costBasis（原幣） |
| `roi` | number | unrealizedPnl / costBasis |
| `priceTime` | string | `marketPrice` 的時間戳 |
| `lastUpdated` | string | 持倉最後更新時間 |
| `currency` | string \| null | 標的計價幣別（`USD` / `TWD` / `EUR` / `JPY`），即上列原幣金額的幣別；資產屬性缺失（理論上不會發生）時為 `null`，該持倉不計入基準幣總額並計入 `fxExcludedCount` |
| `assetType` | string \| null | 資產類型（`STOCK` / `CRYPTO` / `FX` / `BOND`）；資產屬性缺失時為 `null` |
| `sector` | string \| null | 產業分類；`null` 代表未分類，由前端顯示「未分類」（後端不回在地化字串） |
| `market` | string \| null | 〔quick-261107-1j4〕交易市場代號，即資產的 `assets.market`（如 `US` / `TW` / `JP` / `DE` / `CRYPTO` / `FX`，與 `GET /assets` 的 `market` 同值域），與 `sector` 取自同一份資產屬性；前端可配合 `assetType` 決定「該市場的今天」（交易日規則見〈GET /api/v1/assets — 標的報價欄位〉），不必再以代號查一次資產。資產屬性缺失（理論上不會發生，此時與 `currency` / `assetType` / `sector` 同為 `null`）或資產未設定市場（`assets.market` 欄位可為 NULL；種子資料皆有值）時為 `null`，前端應退回預設日期規則 |
| `baseCurrency` | string | 使用者基準幣；`marketValueInBase` 的幣別 |
| `marketValueInBase` | number \| null | `marketValue` 折算為 `baseCurrency` 的值；**換不到匯率時為 `null`** |
| `unrealizedPnlInBase` | number \| null | 〔quick-261106-fbs〕`unrealizedPnl` 以**目前匯率**折算為 `baseCurrency` 的值（scale 8、HALF_UP，與 `marketValueInBase` 同一條折算路徑）；與 `marketValueInBase` **同條件為 `null`**（`currency` 為 `null` 或換不到匯率，語意同 summary 的 `fxExcludedCount`：不計入任何基準幣總額 / 排行）。跨標的比較 / 排行請用此欄 |
| `previousClose` | number \| null | 前收（原幣）：該標的所屬市場交易日起點之前的最後一筆成交；沒有前收或查無行情時為 `null` |
| `dayPricePnl` | number \| null | 單檔今日價格損益（原幣），規則見〈今日價格損益〉；計算所需價格缺失時為 `null` |
| `dayPricePnlRatio` | number \| null | `dayPricePnl / dayPnlBasis`，**比值而非百分比**；分母為 0 或損益為 `null` 時為 `null` |
| `dayPnlBasis` | number \| null | 今日損益比率的分母（原幣）= 前收時市值 + 今日買入成本；供 summary 折算後計算總額比率，bearer client 可忽略 |

**估值用的最新價**：取自 market-data（Redis 即時快取 → `market_prices` 最新一列）。查無任何行情時退回平均成本估值——此時 `marketPrice` 等於 `avgCost`、`unrealizedPnl` 為 0、`priceTime` 等於 `lastUpdated`。前端若要區分「沒有行情」可比對這兩個時間。

## GET /api/v1/portfolio/summary — 投資組合摘要

權限：`PORTFOLIO_VIEW`。回應：`PortfolioSummaryDto`。

> ⚠️ **2026-10-24 單位語意變更（Phase 04.1 D-05）**：所有金額欄位改以 `baseCurrency`（使用者基準幣）表示；先前版本是未折算的混幣加總（USD 與 TWD 直接相加，錯誤）。欄位名不變，bearer client 與前端須改讀 `baseCurrency` 判斷單位。
>
> ⚠️ **2026-11-01 語意變更（Phase 04.2 D-01，〔04.2〕；04.2-02 決策閘門裁決日）**：`totalPnl` 改為**含現金股利**（`realizedPnl + unrealizedPnl + dividendIncome`）；`roi` 公式不變（`totalPnl / totalCostBasis`），因此 `roi` 也隨之**含股利**——欄位名不變但數值語意改變。新增 `dividendIncome`、`dividendTaxWithheld`；快取鍵升 `v3`（見〈快取與一致性〉）。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `totalMarketValue` | number | 所有持倉 marketValue 逐筆折算為基準幣後加總（換不到匯率的持倉不計入） |
| `totalCostBasis` | number | 所有持倉 costBasis 逐筆折算為基準幣後加總（同上） |
| `realizedPnl` | number | **含已平倉部位**；逐資產依各自幣別折算為基準幣後加總（不是 SQL 跨幣別 sum） |
| `unrealizedPnl` | number | totalMarketValue − totalCostBasis（基準幣） |
| `totalPnl` | number | realizedPnl + unrealizedPnl + dividendIncome（基準幣）。〔04.2〕2026-11-01 起含現金股利；此前為 realizedPnl + unrealizedPnl |
| `roi` | number | totalPnl / totalCostBasis（兩者同為基準幣；totalCostBasis 為 0 時為 0）。公式不變，但因 `totalPnl` 含股利，〔04.2〕2026-11-01 起 **`roi` 含股利**（語意變更，Overview「總報酬率」即綁此欄）。**不含現金** |
| `dividendIncome` | number | 〔04.2〕現金股利收入（基準幣）：Σ 各標的**實領淨額**（`amount`，沖銷列反號）× **目前匯率**；換不到匯率的標的不計入並列入 `unconvertedCurrencies`（與 realizedPnl 同規則）；沒有股利時為 0 |
| `dividendTaxWithheld` | number | 〔04.2〕預扣稅合計（基準幣）：Σ 各標的 `withholdingTax`（沖銷列反號）× 目前匯率；僅供顯示，**不**計入 `totalPnl`（`amount` 已是扣稅後實領） |
| `holdingCount` | number | 持有數量大於 0 的標的數（含換不到匯率而未計入總額者） |
| `baseCurrency` | string | 上列金額欄位所使用的基準幣（`USD` / `TWD` / `EUR` / `JPY`） |
| `fxExcludedCount` | number | 持有數量大於 0、但換不到匯率而**未計入**總額的持倉檔數 |
| `unconvertedCurrencies` | string[] | 換不到匯率的幣別代碼（持倉、已實現損益、今日損益、現金或〔04.2〕股利），字母排序、去重；全部可換算時為空陣列 |
| `dayPricePnl` | number \| null | 今日價格損益總額（基準幣、以目前匯率折算）；`null` = 缺資料，`0` = 真實的零，規則見〈今日價格損益〉 |
| `dayPricePnlRatio` | number \| null | 今日價格損益比率 = Σ損益 / Σ前收時市值與買入成本（皆基準幣），**比值而非百分比**；總額為 `null` 或分母為 0 時為 `null` |
| `dayPnlExcludedCount` | number | 因計算所需價格缺失而未計入今日損益的檔數；換不到匯率者改計入 `fxExcludedCount` / `unconvertedCurrencies`，不在此數內（持有中但查無資產屬性的持倉同理，只計入 `fxExcludedCount`）。〔quick-261106-fbs〕今天賣光但查無資產屬性的平倉候選也計入此數 |
| `cash` | number | 可用現金合計（基準幣）：各幣別現金餘額以目前匯率折算後加總；**可為負**；不含在 `roi` 內，規則見〈可用現金〉 |
| `totalEquity` | number | 總資產 = `totalMarketValue + cash`（基準幣） |
| `cashBalances` | `CashBalanceDto[]` | 各幣別的原幣現金餘額，依 `CurrencyCode` 宣告順序（`USD`、`TWD`、`EUR`、`JPY`），只含有過入出金或交易的幣別；沒有任何紀錄時為空陣列（不是 `null`） |
| `allocation` | `AllocationSliceDto[]` | 類別配置（donut）：資產類型分塊加上正現金，規則見〈資產配置與產業分組〉 |
| `sectorAllocation` | `SectorAllocationDto[]` | 產業配置：依 `sector` 原值分組（不含現金），規則見〈資產配置與產業分組〉 |

### 今日價格損益

- **只含價格損益**：不含匯差、不扣手續費、〔04.2〕**不含股利**（現金股利只進 `dividendIncome` / `totalPnl`）；總額以**目前匯率**折算各檔的原幣損益後加總（`dayPricePnl` 與 `dayPricePnlRatio` 皆然）。
- 「今日」依**各檔所屬市場的交易日**（`TradingDay`：股票依交易所時區午夜、外匯紐約 17:00、加密 UTC 00:00，與報價卡同一條規則），不是統一的日曆日。
- 單檔損益 = `Q1 × 最新價 − Q0 × 前收 − Σ今日買入(數量 × 價) + Σ今日賣出(數量 × 價)`，其中 `Q1` 為目前持有數量、`Q0` 為交易日起點持有數量（由 `Q1` 與今日成交反推，不需要日級快照表）。
- **前收只在 `Q0 > 0` 時必要**：今天才首次買入的標的（`Q0 = 0`）沒有前收也算得出來，損益是以最新價相對成交價；`Q0 > 0` 而無前收，或查無最新價時，該檔 `dayPricePnl` 為 `null`，且**不計入**總額（計入 `dayPnlExcludedCount`）。
- 〔04.2〕**配股 / 拆股生效當日**：反推 `Q0` 時以當日生效的股數調整修正（扣除非現金股數變動，等同前收依比例調整），否則配股 / 拆股會被算成假性暴漲 / 暴跌；單檔與總額（含今日賣光的平倉候選）一致。若該檔行情最新時間**早於**調整生效時間（行情尚未反映調整），該檔 `dayPricePnl` 為 `null` 並計入 `dayPnlExcludedCount`。
- **今天賣光的部位計入總額**：它不在持倉列表，但其損益仍屬於今天，否則當日停損的損失會在總額裡憑空消失。
- 已知限制：今天賣光的部位只在 `last_updated` 於近 7 天內的平倉列中尋找（涵蓋週末與連假）；某市場連續 7 天以上沒有任何成交時，邊界外的賣出不計入。
- **總額的 `null` 與 `0`**：沒有任何候選（無持倉且今日無成交）→ `0`；有候選但全部不計入 → `null`（缺資料，不塞 0）；否則為已計入者的總和，其餘檔數見 `dayPnlExcludedCount`。

### 可用現金

- 現金是**記帳推導值，不是券商帳戶**：`cashBalances[currency]` = 該幣別入出金淨額（沖銷是反向分錄，自然抵銷）＋ 該幣別標的的全部交易現金流（賣出 = `數量 × 價 − 手續費`，買入 = `−(數量 × 價 + 手續費)`）＋ 〔04.2〕該幣別標的的現金股利實領淨額（`amount`，**標的幣別**；沖銷列反號）；幣別取自標的的交易幣別。系統不存任何餘額欄位。
- 〔04.2〕現金股利**不是**入出金分錄：不出現在 `GET /portfolio/cash-entries`，列在 `GET /portfolio/activities`（`kind = CASH_DIVIDEND`）。
- **不驗證餘額、不阻擋任何操作**（D-01）：只買不入金時現金為負，照實回報、不夾成 0，由前端提示。
- `cash` 為各幣別餘額以目前匯率折成基準幣後的加總；換不到匯率的幣別不計入 `cash`，列入 `unconvertedCurrencies`，其原幣餘額仍在 `cashBalances`。
- `CashBalanceDto`：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `currency` | string | `USD` / `TWD` / `EUR` / `JPY` |
| `amount` | number | 該幣別的原幣現金餘額，scale 8；可為負 |

### 資產配置與產業分組

分組由後端以基準幣計算，匯率只在後端折算一次，前端**不需要也不應該**自行換匯或重算。兩者只涵蓋「可折算、持有數量大於 0」的持倉（換不到匯率者不進配置，與總額一致）。

**兩個分母不同**（刻意的）：

| | 分母 | 現金 |
|---|---|---|
| `allocation` | 可折算持倉市值 + 正現金 | 現金 > 0 才成為 `CASH` 一塊；現金 ≤ 0 不列，權重以持倉合計為分母 |
| `sectorAllocation` | 可折算持倉市值 | 不含現金 |

分母為 0 時兩者皆為空陣列。權重為**比值 0..1（不是百分比）**，scale 8；金額為基準幣，scale 8。

`AllocationSliceDto`（`allocation` 的元素）：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `category` | string | `STOCK` / `CRYPTO` / `BOND` / `FX`（`assetType` 常數名）或 `CASH`；沒有持倉的類別不出現 |
| `value` | number | 該類別的基準幣價值 |
| `weight` | number | `value / 分母` |

排序：`value` 降冪，同值依 `category` 字母升冪。

`SectorAllocationDto`（`sectorAllocation` 的元素）：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `sector` | string \| null | 直接取 `assets.sector` 原值；**`null` 代表未分類**，由前端顯示「未分類」（後端不回在地化字串） |
| `value` | number | 該產業的基準幣市值 |
| `weight` | number | `value / 可折算持倉市值` |
| `holdingCount` | number | 該產業內的持倉檔數 |
| `unrealizedPnl` | number | 該產業的基準幣未實現損益（折算後市值 − 折算後成本） |

排序：`value` 降冪，同值依 `sector` 字母升冪，**`sector = null` 一律排最後**（即使金額最大）。

### 匯率規則

- 換算順序固定：同幣別 → **直接列** → 反向列（取倒數）→ 經 USD 三角換算 → 換不到。直接列優先於反向列，例如 `TWD → USD` 取 seed 的 `0.030847`，而不是 `1 / 32.418`。
- 匯率來源為 `fx_rates` 表，**目前是靜態 seed**（不隨市場更新）；換算結果取 8 位小數、四捨五入（HALF_UP）。
- 換不到匯率的持倉**不計入**任何總額，並以 `fxExcludedCount` 與 `unconvertedCurrencies` 回報；前端應據此提示「部分持倉未計入」，而不是默默少算。已實現損益為 0 的資產不需要匯率。

## POST /api/v1/portfolio/cash-entries — 記一筆入金或出金

權限：`TRADE_EXECUTE`。這是**記帳**，不是券商帳戶：不驗證餘額、不阻擋出金（餘額可因此為負，見〈可用現金〉）。分錄是 **append-only**：不能修改或刪除，更正的唯一方式是**沖銷**（見下）；資料庫層以 trigger 強制，應用程式 bug 也改不掉既有分錄。

### Header

| 名稱 | 必填 | 規則 |
|---|---|---|
| `Idempotency-Key` | **是** | 1–128 字元，不得全空白。同一使用者、同一把鍵只會建立一筆分錄。缺 header 或空白／過長 → 400 `VALIDATION_FAILED`，`error.fields['Idempotency-Key']` |

冪等語意（與 `POST /trades` 同強度）：

- 同一把鍵、**相同 payload** 重送 → 回傳**既有那筆分錄**（200），不重複建立。沖銷成功後以同一把鍵重送，同樣回同一筆（冪等優先於「已沖銷」）。
- 同一把鍵、**不同 payload** → 409 `CASH_IDEMPOTENCY_KEY_REUSED`，不寫入。
- payload 比對維度：`type`、`currency`、`amount`（以大小比較，`100` 等於 `100.00`）、`occurredAt`（**僅在請求有送時**比對，時間截到微秒）、沖銷對象（`reversalOfId`；同鍵換對象，或在沖銷與一般分錄之間切換，皆視為不同 payload）。**不含 `note`**。

### Body — `CreateCashEntryRequest`

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `type` | string | 是 | `DEPOSIT` 或 `WITHDRAWAL`，大小寫不敏感 |
| `currency` | string | 是 | `USD` / `TWD` / `EUR` / `JPY`，大小寫不敏感 |
| `amount` | number | 是 | 0.00000001 ≤ x ≤ 1,000,000,000,000（1e12，含上限）；整數最多 13 位、小數最多 8 位 |
| `note` | string | 否 | 最多 500 字；不納入冪等比對 |
| `occurredAt` | string（ISO-8601） | 否 | 省略視為伺服器現在；不得晚於伺服器時間 5 分鐘以上；〔quick-261107-1j4〕不得早於 `1900-01-01T00:00:00Z`（含邊界、比較 instant；含帶號擴充年份）→ 400 `fields.occurredAt`（`must not be before 1900-01-01`），在任何 repository 呼叫之前擋下 |
| `reversalOfId` | string（UUID） | 否 | 帶值代表這是一筆**沖銷**，見下 |

**沖銷（`reversalOfId`）**：

- 沖銷對象必須是登入者自己的分錄；他人的分錄與不存在的分錄一律回 404，無法區分。
- 沖銷分錄必須與原分錄鏡像：`type` 相反、`currency` 相同、`amount` 數值相等（compareTo，scale 無關），否則 400 `fields.reversalOfId = "must mirror the original entry"`。`note` 與 `occurredAt` 可自填。
- 一筆分錄只能被沖銷一次（資料庫唯一約束保證，併發雙沖銷只有一筆成功，另一筆回 409）；**沖銷分錄本身不能再被沖銷**。
- 前端「沖銷這筆」的顯示條件：`reversalOfId == null && !reversed`。
- 非 null 的 `reversalOfId` 一律必須是合法 UUID（空白字串也是 `invalid format`），不會被當作「沒帶」而變成一筆真的出金。

### 回應 — `CashEntryDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | 分錄 UUID |
| `type` | string | `DEPOSIT` / `WITHDRAWAL` |
| `currency` | string | `USD` / `TWD` / `EUR` / `JPY` |
| `amount` | number | 恆為正 |
| `note` | string \| null | |
| `occurredAt` | string | 分錄發生時間 |
| `createdAt` | string | 寫入時間 |
| `reversalOfId` | string \| null | 被沖銷的原分錄 UUID；一般分錄為 `null` |
| `reversalOfOccurredAt` | string \| null | 被沖銷的原分錄發生時間；一般分錄為 `null` |
| `reversed` | boolean | 此分錄是否已被沖銷。建立當下的回應恆為 `false`（冪等重送回的也是首次回應的形狀，不反映之後才發生的沖銷） |

**不含 `idempotencyKey`**。

### 錯誤

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `error.fields` 的鍵：`Idempotency-Key`、`type`（`unsupported type`）、`currency`（`unsupported currency`）、`amount`、`note`、`occurredAt`（`must not be in the future` / `must not be before 1900-01-01`）、`reversalOfId`（`invalid format` / `must mirror the original entry`；超過 36 字元為 bean validation 訊息） |
| 400 | `VALIDATION_FAILED` | body 不是合法 JSON 或欄位型別錯；訊息固定、無 `error.fields` |
| 404 | `CASH_ENTRY_NOT_FOUND` | 沖銷對象不存在，或屬於他人（兩者無法區分，body 不含該 UUID） |
| 409 | `CASH_IDEMPOTENCY_KEY_REUSED` | 同一把鍵搭配不同 payload |
| 409 | `CASH_ENTRY_ALREADY_REVERSED` | 沖銷對象已被沖銷（含併發落敗：另一把鍵搶先沖銷） |
| 409 | `CASH_ENTRY_NOT_REVERSIBLE` | 沖銷對象本身是沖銷分錄 |
| 403 | `AUTH_FORBIDDEN` | 缺 `TRADE_EXECUTE` 權限 |
| 429 | `AUTH_RATE_LIMITED` | 一般 API 限流 |

錯誤訊息為靜態字串，不回射使用者輸入（含冪等鍵、備註與 UUID）。前端依 `code` 分派文案，這些 code 字面不會更名。

### 稽核

一般入出金記為 `cash_entry_create`，沖銷記為 `cash_entry_reverse`；成功的 target 為 `cash_entry:<id>`、result 為 `success`，失敗的 target 為 `cash_entry`、result 為 `failure:<code>`。失敗稽核只涵蓋進入 controller 後拋出的業務錯誤（例如冪等鍵衝突、沖銷規則）；Bean Validation 400 與缺少 `Idempotency-Key` header 在進入 controller 方法前就被擋下，不會有稽核紀錄。

## GET /api/v1/portfolio/cash-entries — 入出金紀錄

權限：`PORTFOLIO_VIEW`。只含登入者自己的分錄。

| 參數 | 預設 | 規則 |
|---|---|---|
| `page` | `0` | 夾限至 0..10000；非數字 → 400 `VALIDATION_FAILED`（`page must be a number`，無 `fields`） |
| `size` | `20` | 夾限至 1..100；非數字 → 400 `VALIDATION_FAILED`（`size must be a number`，無 `fields`） |

排序固定為 `occurredAt` 新到舊、同時間以 `id` 新到舊，沒有排序或篩選參數。回應：`PageResponse<CashEntryDto>`；沖銷分錄帶 `reversalOfId` 與 `reversalOfOccurredAt`，被沖銷的原分錄 `reversed = true`。

## POST /api/v1/portfolio/dividends — 記一筆現金股利 〔04.2〕

權限：`TRADE_EXECUTE`。資料表 `cash_dividends`（**新表，不擴充 `transactions.type`**，D-13）為 **append-only**：不能修改或刪除，更正的唯一方式是**沖銷**；資料庫層以 trigger 強制（重用 V15 的 `prevent_append_only_mutation()`），沖銷規則另有 DB 驗證 trigger 與唯一約束。股利**不動股數與平均成本**，只影響可用現金（標的幣別）與 summary 的 `dividendIncome` / `totalPnl`。

### Header

| 名稱 | 必填 | 規則 |
|---|---|---|
| `Idempotency-Key` | **是** | 1–128 字元，不得全空白。同一使用者、同一把鍵只會建立一筆。缺 header 或空白／過長 → 400 `VALIDATION_FAILED`，`error.fields['Idempotency-Key']` |

冪等語意（與 `POST /trades`、cash-entries 同強度）：

- 同一把鍵、**相同 payload** 重送 → 回傳**既有那筆**（200），不重複建立、**不失效快取**。
- 同一把鍵、**不同 payload** → 409 `DIVIDEND_IDEMPOTENCY_KEY_REUSED`，不寫入。
- payload 比對維度：解析後的標的（assetId）、`amount`（compareTo，`100` 等於 `100.00`）、`withholdingTax`（compareTo；省略視為 0）、`paidAt`（**僅在請求有送時**比對，截到微秒）、`reversalOfId`。**不含 `note`**。

### Body — `CreateDividendRequest`

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `symbol` | string | 是 | 不得空白、最多 32 字；須為存在的標的，否則 404 `ASSET_NOT_FOUND`（`fields.symbol = not found`）。〔quick-261107-1j4，04.2 WR-01〕**新紀錄**須 active 且 tradeable（否則 404 `fields.symbol = not tradeable`）；帶 `reversalOfId` 的沖銷與同 key 冪等重送**不檢查標的狀態**（下架後仍可更正、重送仍拿回同一列） |
| `amount` | number | 是 | **實領總額**（扣稅後、標的幣別）：0.00000001 ≤ x ≤ 1,000,000,000,000；整數最多 13 位、小數最多 8 位 |
| `withholdingTax` | number | 否 | 預扣稅（標的幣別）：0 ≤ x ≤ 1,000,000,000,000；整數最多 13 位、小數最多 8 位；省略視為 0 |
| `paidAt` | string（ISO-8601） | 否 | 入帳時間；省略視為伺服器現在；晚於伺服器時間 5 分鐘以上 → 400 `fields.paidAt`；〔quick-261106-fbs〕早於 `1900-01-01T00:00:00Z`（含帶號擴充年份如 `-10000-01-01T00:00:00Z`）→ 400 `fields.paidAt`（`must not be before 1900-01-01`，下限含邊界） |
| `note` | string | 否 | 最多 500 字；不納入冪等比對 |
| `reversalOfId` | string（UUID） | 否 | 最多 36 字；帶值代表這是一筆**沖銷**，見下 |

- **無持倉或已賣光仍可記**（D-03）：股利常在賣出後才入帳。
- 幣別不由請求指定，一律取標的的交易幣別（經 `AssetFacade`）。

**沖銷（`reversalOfId`）**：

- 帶原紀錄的 UUID；`symbol`、`amount`、`withholdingTax` 必須與原列**相同**（數值 compareTo），否則 400 `VALIDATION_FAILED`（`error.fields` 指名不符欄位）；`paidAt` 省略 = 現在，`note` 可自填。
- 原列不存在或屬於他人 → 404 `DIVIDEND_NOT_FOUND`（兩者無法區分）；原列已被沖銷 → 409 `DIVIDEND_ALREADY_REVERSED`（DB 唯一約束保證，併發雙沖銷只有一筆成功）；原列本身是沖銷 → 409 `DIVIDEND_NOT_REVERSIBLE`。
- 沖銷列在資料庫中存與原列**相同的正值**，所有聚合（現金、`dividendIncome`、`dividendTaxWithheld`）以 `reversal_of_id IS NOT NULL` 反號。
- 前端「沖銷這筆」的顯示條件：`reversalOfId == null && !reversed`（活動列表直接看 `reversible`）。

### 回應 — `DividendDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | 股利紀錄 UUID |
| `symbol` | string | |
| `currency` | string | 標的交易幣別（`amount` / `withholdingTax` 的幣別） |
| `amount` | number | 實領總額，恆為正（沖銷列亦同，以 `reversalOfId` 判斷） |
| `withholdingTax` | number | 預扣稅，≥ 0 |
| `paidAt` | string | 入帳時間 |
| `createdAt` | string | 寫入時間 |
| `note` | string \| null | |
| `reversalOfId` | string \| null | 被沖銷的原紀錄 UUID；一般紀錄為 `null` |
| `reversed` | boolean | 此紀錄是否已被沖銷；建立當下的回應恆為 `false` |

**不含 `idempotencyKey`**。

### 錯誤

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `error.fields` 的鍵：`Idempotency-Key`、`symbol`、`amount`、`withholdingTax`、`paidAt`（`must not be in the future` / `must not be before 1900-01-01`）、`note`、`reversalOfId`（`invalid format`）；沖銷值不符時指名不符欄位 |
| 400 | `VALIDATION_FAILED` | body 不是合法 JSON 或欄位型別錯；訊息固定、無 `error.fields` |
| 404 | `ASSET_NOT_FOUND` | 標的不存在（`fields.symbol = not found`）；或新紀錄的標的 inactive / 不可交易（`fields.symbol = not tradeable`）。沖銷與同 key 冪等重送不檢查標的狀態（04.2 WR-01） |
| 404 | `DIVIDEND_NOT_FOUND` | 沖銷對象不存在或屬於他人 |
| 409 | `DIVIDEND_IDEMPOTENCY_KEY_REUSED` | 同一把鍵搭配不同 payload |
| 409 | `DIVIDEND_ALREADY_REVERSED` | 沖銷對象已被沖銷（含併發落敗） |
| 409 | `DIVIDEND_NOT_REVERSIBLE` | 沖銷對象本身是沖銷紀錄 |
| 403 | `AUTH_FORBIDDEN` | 缺 `TRADE_EXECUTE` 權限 |
| 429 | `AUTH_RATE_LIMITED` | 一般 API 限流 |

錯誤訊息為靜態英文字串，不回射使用者輸入（含冪等鍵、備註與 UUID）。前端依 `code` 分派文案。

### 稽核

一般股利記為 `dividend_create`，沖銷記為 `dividend_reverse`；成功 target 為 `dividend:<id>`、result `success`，失敗 target 為 `dividend`、result `failure:<code>`。範圍與 cash-entries 相同（Bean Validation 400 與缺 header 不留稽核）。

## POST /api/v1/portfolio/share-adjustments — 記一筆配股或拆股 / 合股 〔04.2〕

權限：`TRADE_EXECUTE`。資料表 `share_adjustments`（**新表，不擴充 `transactions.type`**，D-13）為 **append-only**，更正只能沖銷。配股與拆股共用同一條「**股數變、總成本不變**」規則：`新股數 = 舊股數 + quantityDelta`、`新均價 = 舊股數 × 舊均價 / 新股數`（scale 8、HALF_UP）；`realizedPnl` 不變。

### Header

同 dividends：`Idempotency-Key` 必填，1–128 字元。

### Body — `CreateShareAdjustmentRequest`

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `symbol` | string | 是 | 不得空白、最多 32 字；須為存在的標的，否則 404 `ASSET_NOT_FOUND`（`fields.symbol = not found`）。〔quick-261107-1j4，04.2 WR-01〕**新紀錄**須 active + tradeable（否則 404 `fields.symbol = not tradeable`，在鎖持倉之前）；帶 `reversalOfId` 的沖銷與同 key 冪等重送**不檢查標的狀態** |
| `type` | string | 是 | `STOCK_DIVIDEND`（配股）或 `SPLIT`（拆股 / 合股）；白名單外 → 400 `fields.type` |
| `quantity` | number | `STOCK_DIVIDEND` 必填 | 實領股數：0.00000001 ≤ x ≤ 1,000,000,000；整數最多 10 位、小數最多 8 位 |
| `ratioNew` / `ratioOld` | integer | `SPLIT` 必填 | 比例 new:old，各 1–1000；兩者相等 → 400 `fields.ratio`（例：1 拆 4 = `4:1`；10 合 1 = `1:10`） |
| `effectiveDate` | string（`yyyy-MM-dd`） | 是 | 除權日（該標的市場的日曆日） |
| `note` | string | 否 | 最多 500 字；不納入冪等比對 |
| `reversalOfId` | string（UUID） | 否 | 最多 36 字；帶值代表沖銷 |

### 生效時間與時序規則

- `effective_at = TradingDay.of(assetType, market).startOf(effectiveDate)`：除權日**該市場交易日的起點**（股票為交易所時區午夜、外匯紐約 17:00、加密 UTC 00:00，與今日損益同一條規則）。
- `effective_at` 晚於伺服器時間 5 分鐘以上 → 400 `fields.effectiveDate`。
- 〔quick-261106-fbs〕`effectiveDate` 早於 `1900-01-01`（下限含邊界）→ 400 `fields.effectiveDate`（`must not be before 1900-01-01`），在解析標的之前擋下；先前 DB 存不下的極端日期會變成 500。
- `effective_at` 早於該標的最晚一筆「**未被沖銷且非沖銷列**」調整的 `effective_at` → 400 `fields.effectiveDate`（調整只能依時間往後記；要補記更早的，先沖銷較晚者）。
- **同一除權日可記多筆調整，依記錄順序疊加**（2026-11-04 裁決）：與最晚有效調整同一 `effective_at` 不算「早於」；後記錄者的 `Q_asOf` 包含同一 `effective_at`、已先記錄的調整。例：100 股同一除權日記兩次 2:1 拆股 → 第一筆 ΔQ +100、第二筆 ΔQ +200，結果 400 股。「最晚」依 (`effective_at` DESC, 記錄順序 DESC) 排序。
- 事件日起點持股 `Q_asOf` = `executed_at` **早於** `effective_at` 的交易淨股數 + `effective_at` **不晚於**本筆生效瞬間的既有調整淨股數（沖銷列反號；本筆尚未寫入，因此只含先記錄者）。`Q_asOf` ≤ 0，或目前持倉不存在 / ≤ 0 → 409 `TRADE_INSUFFICIENT_HOLDING`（D-06 / D-09）。
- 對應地，`POST /trades` 不接受早於最晚未沖銷調整生效起點的 `executedAt`（見該節〈錯誤〉）。

### `quantityDelta` 計算式

| type | quantityDelta |
|---|---|
| `STOCK_DIVIDEND` | `quantity` |
| `SPLIT` | `Q_asOf × ratioNew / ratioOld`（scale 8、**DOWN**；零股以小數保留）− `Q_asOf` |

`quantityDelta` 由後端計算並寫入，**不在請求中**；拆股依除權日起點持股計算，除權日之後的交易不參與換算。

> **配股攤薄的捨入誤差**：1000 股 @100 配 50 股 → 1050 股 @**95.23809524**；`1050 × 95.23809524 = 100000.00000200`，與原總成本 100000 差 0.000002——這是 scale-8 均價的捨入，不是錯帳，前端不要以「總成本恰好相等」做斷言。

**沖銷（`reversalOfId`）**：

- 值（`symbol` / `type` / `quantity` 或 `ratioNew` + `ratioOld` / `effectiveDate`）必須與原列相同，否則 400 `VALIDATION_FAILED`（`fields` 指名）。
- **只能沖銷該標的最晚一筆未沖銷調整**；沖銷非最晚者，或原列本身是沖銷 → 409 `SHARE_ADJUSTMENT_NOT_REVERSIBLE`（沖銷中間一筆會讓後續調整的 ΔQ 基準失真）。同一除權日的多筆依記錄順序**倒著**沖：先沖最後記錄的那筆，才能再沖前一筆。
- 沖銷**撤銷已記錄的 `quantityDelta`、不重算比例**：持倉回到調整前股數、總成本不變。沖銷後持股 ≤ 0 → 409 `TRADE_INSUFFICIENT_HOLDING`。
- 不存在 / 他人 → 404 `SHARE_ADJUSTMENT_NOT_FOUND`；已被沖銷 → 409 `SHARE_ADJUSTMENT_ALREADY_REVERSED`。
- 沖銷列在資料庫中存與原列相同的值（含 `quantity_delta`、`effective_date`、`effective_at`），聚合時反號。

冪等語意：同 key 同 payload → 回既有列；不同 payload → 409 `SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED`。比對維度：assetId、`type`、`quantity`（配股，compareTo）或 `ratioNew` + `ratioOld`（拆股）、`effectiveDate`、`reversalOfId`；**不含** `note` 與後端算出的 `quantityDelta`。

### 回應 — `ShareAdjustmentDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | UUID |
| `symbol` | string | |
| `currency` | string | 標的交易幣別 |
| `type` | string | `STOCK_DIVIDEND` / `SPLIT` |
| `quantityDelta` | number | 後端算出的股數變動，**帶號**（合股為負）；沖銷列輸出原值 |
| `ratioNew` / `ratioOld` | integer \| null | 僅 `SPLIT` |
| `effectiveDate` | string | `yyyy-MM-dd` |
| `createdAt` | string | |
| `note` | string \| null | |
| `reversalOfId` | string \| null | 被沖銷的原列 UUID |
| `reversed` | boolean | 是否已被沖銷；建立當下恆為 `false` |

**不含 `idempotencyKey`**。

### 錯誤

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `error.fields` 的鍵：`Idempotency-Key`、`symbol`、`type`、`quantity`、`ratio`（相等）/ `ratioNew` / `ratioOld`、`effectiveDate`（未來、早於 1900-01-01、早於最晚調整）、`note`、`reversalOfId`；沖銷值不符時指名不符欄位 |
| 400 | `VALIDATION_FAILED` | body 不是合法 JSON 或欄位型別錯；訊息固定、無 `error.fields` |
| 404 | `ASSET_NOT_FOUND` | 標的不存在（`fields.symbol = not found`）；或新紀錄的標的 inactive / 不可交易（`fields.symbol = not tradeable`）。沖銷與同 key 冪等重送不檢查標的狀態（04.2 WR-01） |
| 404 | `SHARE_ADJUSTMENT_NOT_FOUND` | 沖銷對象不存在或屬於他人 |
| 409 | `TRADE_INSUFFICIENT_HOLDING` | 除權日起點或目前無持倉；沖銷後持股 ≤ 0 |
| 409 | `SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED` | 同一把鍵搭配不同 payload |
| 409 | `SHARE_ADJUSTMENT_ALREADY_REVERSED` | 沖銷對象已被沖銷 |
| 409 | `SHARE_ADJUSTMENT_NOT_REVERSIBLE` | 沖銷對象不是該標的最晚一筆未沖銷調整，或本身是沖銷 |
| 403 | `AUTH_FORBIDDEN` | 缺 `TRADE_EXECUTE` 權限 |
| 429 | `AUTH_RATE_LIMITED` | 一般 API 限流 |

### 稽核

`share_adjustment_create` / `share_adjustment_reverse`；target `share_adjustment:<id>` / `share_adjustment`，result 規則同 dividends。

## GET /api/v1/portfolio/activities — 統一活動列表 〔04.2〕

權限：`PORTFOLIO_VIEW`。交易、現金股利、股數調整三表 `UNION ALL` 的唯讀分頁列表，供 Trades 頁「全部 / 買入 / 賣出 / 股利 / 股數調整 / 年度」頁籤與 CSV 使用（皆為後端篩選）。只含登入者自己的紀錄。**`GET /api/v1/trades` 與 `TradeDto` 不變**。

| 參數 | 預設 | 規則 |
|---|---|---|
| `type` | — | `BUY` / `SELL` / `DIVIDEND` / `SHARE_ADJUSTMENT`；省略 = 全部；白名單外 → 400 `fields.type` |
| `symbol` | — | 篩選單一標的 |
| `dateFrom` / `dateTo` | — | 作用於 `occurredAt`，半開區間（下界含、上界不含）；格式與 `GET /trades` 相同 |
| `sort` | `executedAt` | `executedAt`（= `occurredAt`）、`createdAt`、`total`、`quantity` |
| `direction` | `desc` | `asc` / `desc` |
| `page` / `size` | `0` / `20` | 夾限同 `GET /trades` |

排序：所選欄位 `NULLS LAST`（例如依 `total` 排序時，沒有金額的股數調整排最後），tie-breaker 為 `createdAt`、`id`（UUID）。回應：`PageResponse<PortfolioActivityDto>`。

### `PortfolioActivityDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | 原紀錄 UUID（交易 / 股利 / 股數調整各自的 UUID） |
| `kind` | string | `BUY` / `SELL` / `CASH_DIVIDEND` / `STOCK_DIVIDEND` / `SPLIT` |
| `symbol` | string \| null | 資產屬性缺失（理論上不會發生）時為 `null` |
| `currency` | string \| null | 標的交易幣別 |
| `occurredAt` | string | 交易成交時間 / 股利入帳時間 / 股數調整生效 instant（`effective_at`） |
| `effectiveDate` | string \| null | 僅股數調整：除權日 `yyyy-MM-dd` |
| `createdAt` | string | 寫入時間 |
| `quantity` | number \| null | 交易數量 / 配股股數 / 拆股 ΔQ（帶號）；股利為 `null` |
| `price` | number \| null | 僅交易 |
| `fee` | number \| null | 僅交易 |
| `amount` | number \| null | 交易為 `quantity × price`（捨入至 scale 8）；股利為實領總額；股數調整為 `null` |
| `withholdingTax` | number \| null | 僅股利 |
| `ratioNew` / `ratioOld` | integer \| null | 僅拆股 |
| `note` | string \| null | |
| `reversalOfId` | string \| null | 沖銷列指向的原列 UUID |
| `reversalOfOccurredAt` | string \| null | 原列的 `occurredAt` |
| `reversalOfEffectiveDate` | string \| null | 原列的 `effectiveDate`（僅股數調整） |
| `reversed` | boolean | 此列是否已被沖銷（交易恆為 `false`） |
| `reversible` | boolean | 交易恆 `false`；股利 = 非沖銷列且未被沖銷；股數調整 = 同上**且**為該標的最晚一筆未沖銷調整 |

**各 kind 欄位適用表**（✓ = 有值，— = 恆 `null`）：

| 欄位 | BUY / SELL | CASH_DIVIDEND | STOCK_DIVIDEND | SPLIT |
|---|---|---|---|---|
| `effectiveDate` | — | — | ✓ | ✓ |
| `quantity` | ✓ | — | ✓（配股股數） | ✓（ΔQ 帶號） |
| `price` / `fee` | ✓ | — | — | — |
| `amount` | ✓（quantity × price） | ✓（實領） | — | — |
| `withholdingTax` | — | ✓ | — | — |
| `ratioNew` / `ratioOld` | — | — | — | ✓ |
| `reversalOf*` | — | 沖銷列 ✓ | 沖銷列 ✓ | 沖銷列 ✓ |

- 沖銷列的數值欄位輸出**原值**（與原列相同、不反號），前端以 `reversalOfId != null` 判斷並標示；被沖銷的原列 `reversed = true`。
- 列表 count 與 list 於同一個可重複讀的唯讀交易內取得（與 cash-entries 列表相同）。

## 快取與一致性

- 持倉與摘要有 60 秒 Redis 快取，鍵為 `portfolio:valuation:v3:{userId}:{assetId}`（存**原幣**估值，`baseCurrency` / `marketValueInBase` / `unrealizedPnlInBase` 於回應時才補上，所以這三欄的新增不需換鍵；2026-11-07 quick-261107-1j4 由 `v2` 升 `v3`：`market` 隨原幣估值寫入快取，舊 `v2` JSON 缺此欄會被讀成 `null`，換前綴使部署前後兩種形狀互不讀取）與 `portfolio:summary:v3:{userId}:{baseCurrency}`（〔04.2〕2026-11-01 由 `v2` 升 `v3`：summary 新增 `dividendIncome` / `dividendTaxWithheld` 且 `totalPnl` 含股利，換前綴使部署前後兩種形狀互不讀取）。
- summary 依基準幣分鍵：使用者切換基準幣（`PATCH /api/v1/me/preferences`）後，下一次讀取直接 miss、立即拿到新幣別的值，不必等 60 秒。
- 成交後在交易 **commit 之後**失效持倉鍵與 `USD` / `TWD` / `EUR` / `JPY` 四把 summary 鍵，所以成交後立即重讀一定看得到新交易。
- 入出金分錄（含沖銷）在寫入 **commit 之後**只失效上述四把 summary 鍵，**不影響持倉鍵**（分錄不改變持倉估值）；現金項因此留在 summary 快取之內，入金後立即重讀就看得到新的 `cash`。冪等命中與被拒絕的請求（資料未變）不失效。
- 〔04.2〕現金股利（含沖銷）在 commit 之後**只失效 summary 鍵**（股利不動股數與均價）；股數調整（含沖銷）在 commit 之後失效**該標的持倉鍵 + summary 鍵**（與成交相同）。冪等命中與被拒絕的請求不失效。
- 讀取的快照一致性：`GET /trades`、`GET /portfolio/cash-entries` 與 summary 的計算在 `REPEATABLE_READ` 唯讀交易內執行（quick-261106-fbs，04.1 REVIEW WR-03），同一回應內的多道查詢（例如分頁的 count 與 items、summary 的各項加總）看到同一份快照。
- 殘餘競態（與成交路徑相同、已接受）：讀者若在 commit 前讀了資料庫、於失效之後才寫回快取，舊值最長停留 60 秒。
- 鍵中的版本（持倉 `v3`、summary `v3`）讓部署瞬間新舊程式不會讀到對方形狀的 JSON；舊鍵 60 秒內自然過期。
- （歷史，Phase 04.1）**摘要 JSON 在 Phase 04.1 內陸續追加欄位（今日損益、現金、配置）但鍵維持 `v2`**：Phase 04.1 作為一次部署上線，`v2` 鍵在此之前不存在，所以不會有舊形狀的 `v2` JSON。只有「部署過 04.1 中途版本的共用環境」才會有最多 60 秒的視窗，期間命中的舊 `v2` summary 缺新欄位（Jackson 以 `null` 讀入，`allocation` / `sectorAllocation` / `cashBalances` 可能是 `null` 而非空陣列），60 秒 TTL 到期後自動恢復。bearer client 對這些欄位仍應容錯 `null`。
- 前端成交後應重讀 summary、holdings 與 trades（Phase 4 post-trade refetch）；〔04.2〕記股利或股數調整（含沖銷）後同樣重讀 summary、holdings 與活動列表。

## GET /api/v1/assets — 標的報價欄位

報價卡的六個欄位由 market-data 提供,依**該資產市場的交易日**計算(`TradingDay`,對齊 TradingView):
股票依交易所時區午夜(US 紐約、TW 台北、JP 東京、DE 柏林)、外匯紐約 17:00、加密 UTC 00:00。

| 欄位 | 定義 |
|------|------|
| `latestPrice` | 最新成交價(Redis latest → `market_prices`) |
| `change` / `changePercent` | `latestPrice` − 前收;百分比取兩位。前收 = 交易日起點前最後一筆成交,與 1D K 線前一根的 close 相同 |
| `high` / `low` | 目前交易日的最高 / 最低,涵蓋 `latestPrice` |
| `volumeText` | 目前交易日累計量的縮寫(`3.0K`、`52.1M`);無量為 `null` |

目前交易日以最新成交時間決定:收盤後或週末停在最後一個有成交的交易日。
**沒有任何行情的資產六個欄位皆為 `null`**(不塞預設價);沒有前收時只有 `change` / `changePercent` 為 `null`。

## 尚未提供

- 匯兌損益、帳戶層級報酬率 —— Deferred（今日損益只含價格損益；`roi` 不含現金，匯率目前為靜態 seed）。

## 變更紀錄

- 2026-11-09（Phase 5 D-02a，05-09）：`POST /trades` 的 `executedAt` 晚於伺服器時間 5 分鐘以上時，400 `VALIDATION_FAILED` 改帶 `fields.executedAt = "must not be in the future"`（先前無 `fields`、訊息 `executedAt must not be in the future`），與 1900 下限同形，前端下單表單可把錯誤綁到成交時間欄位。HTTP status、`error.code`、5 分鐘容忍度不變；只看 `error.code` 的 client 不受影響。
- 2026-11-07（quick-261107-1j4，Yuan 裁決）：holdings 新增 `market`（string | null，JSON 位置緊接 `sector` 之後）：交易市場代號，與 `sector` 取自同一份資產屬性，資產屬性缺失或資產未設定市場時為 `null`。前端從持倉開啟股利 / 股數調整對話框時直接以 `market` + `assetType` 決定該市場的今天，不再額外依代號查資產。`market` 隨原幣估值寫入持倉快取，所以持倉鍵由 `portfolio:valuation:v2` 升 `portfolio:valuation:v3`（舊鍵 60 秒內自然過期）；summary 形狀與 `portfolio:summary:v3` 不變。純新增欄位，既有 client 不受影響。
- 2026-11-07（quick-261107-1j4，04.2 WR-01）：`POST /portfolio/dividends` 與 `POST /portfolio/share-adjustments` 的標的狀態檢查只套用在**新紀錄**：帶 `reversalOfId` 的沖銷與同 key 冪等重送不再要求標的 active + tradeable（先前標的下架後無法更正既有紀錄，逾時重送也會被 404 擋下）。新紀錄的 404 `ASSET_NOT_FOUND` 訊息與 `fields.symbol` 不變；活動列表的 `reversible` 仍只看帳本。
- 2026-11-07（quick-261107-1j4）：`POST /trades` 的 `executedAt` 與 `POST /portfolio/cash-entries` 的 `occurredAt` 新增下限 1900-01-01T00:00:00Z（含邊界、比較 instant）：早於者回 400 `VALIDATION_FAILED`，`fields.executedAt` / `fields.occurredAt` = `must not be before 1900-01-01`，在資產解析與寫入之前擋下（先前 DB 存不下的極端值會變成 500；形狀同 quick-261106-fbs 的 `paidAt` / `effectiveDate`）。`executedAt` 晚於現在的既有錯誤不變（仍無 `fields`）。
- 2026-11-06（quick-261106-fbs，文件掃描）：〈快取與一致性〉補「讀取的快照一致性」（`listTrades` / `summary` 改 `REPEATABLE_READ`，04.1 WR-03）；檔首 〔04.2〕 凍結提示改為「已實作、程式為事實來源」；D-01 語意變更（`totalPnl` / `roi` 含股利、summary 鍵升 `v3`）的日期由 2026-10-31 對齊 04.2-02 裁決日 2026-11-01（04.2 G2）。
- 2026-11-06（quick-261106-fbs）：holdings 新增 `unrealizedPnlInBase`（基準幣未實現損益；與 `marketValueInBase` 同條件為 `null`、回應時補上、不寫入持倉快取，持倉鍵維持 `portfolio:valuation:v2`，summary 與 `portfolio:summary:v3` 不變），供前端 Top movers 以基準幣排序。`dayPnlExcludedCount` 補計「今天賣光但查無資產屬性的平倉候選」（先前既不計入總額也不計入排除數）；持有中且查無資產屬性的持倉只計入 `fxExcludedCount`，不再於價格缺失時被重複計入（04.1 REVIEW IN-05）。`POST /portfolio/dividends` 的 `paidAt` 與 `POST /portfolio/share-adjustments` 的 `effectiveDate` 新增下限 1900-01-01（含）：早於者回 400 `fields.paidAt` / `fields.effectiveDate`，不再以 500 結束（04.2 REVIEW WR-04）。
- 2026-11-04（Phase 04.2，04.2-08）：股數調整〈生效時間與時序規則〉明定**同一除權日的多筆調整依記錄順序疊加**（Yuan 裁決「同日依記錄順序疊加」）：`Q_asOf` 的調整段改為含同一 `effective_at`、已先記錄者（交易段仍為嚴格早於）；沖銷依記錄順序倒著來。修正前同日第二筆拆股的 `Q_asOf` 不含第一筆（100 股兩次 2:1 → 300 而非 400）。
- 2026-11-01（Phase 04.2，04.2-02）：凍結 `POST /portfolio/dividends`、`POST /portfolio/share-adjustments`、`GET /portfolio/activities` 三節，summary 新增 `dividendIncome` / `dividendTaxWithheld`、`totalPnl` 與 `roi` 含股利（語意變更）、summary 快取鍵 `v3`、`POST /trades` 新增 `fields.executedAt` 早於最晚股數調整的 400。股利與股數調整**實作為新表 `cash_dividends` / `share_adjustments`，而非擴充 `transactions.type`**（先前〈尚未提供〉的「`transactions.type` 擴充」敘述已刪除；裁決見 `.planning/phases/04.2-dividends-share-adjustments-watchlist/04.2-02-SUMMARY.md`）。Watchlist 契約另立 [watchlist-contract.md](watchlist-contract.md)。
