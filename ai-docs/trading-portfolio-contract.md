# Trading & Portfolio API Contract

> 後端是權威（judgment §4）。本檔記錄 `stock-module-trading` 對前端公開的契約：端點、DTO 欄位、驗證規則、錯誤碼與序列化格式。
> 認證、CSRF、401/403 信封見 [browser-auth-contract.md](browser-auth-contract.md)；本檔只談交易與持倉本身。
>
> 程式碼是事實來源。與本檔衝突時以程式碼為準，並回頭修本檔。
> 權威檔案：`TradingController`、`CreateTradeRequest`、`TradeDto`、`HoldingDto`、`PortfolioSummaryDto`、`TradingService`、`PortfolioQueryService`、
> `CashLedgerController`、`CreateCashEntryRequest`、`CashEntryDto`、`CashBalanceDto`、`AllocationSliceDto`、`SectorAllocationDto`、`CashLedgerService`、`ErrorCode`。
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
| `executedAt` | string（ISO-8601） | 否 | 省略視為現在；不得晚於伺服器時間 5 分鐘以上（時鐘偏移容忍） |

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

> 既有金額欄位（`avgCost`、`costBasis`、`marketPrice`、`marketValue`、`realizedPnl`、`unrealizedPnl`）為**原幣**，幣別見 `currency`。需要跨標的比較或計算權重時請用 `marketValueInBase`（已折算為使用者基準幣）。今日欄位（`previousClose`、`dayPricePnl`、`dayPnlBasis`）同為原幣。

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
| `currency` | string | 標的計價幣別（`USD` / `TWD` / `EUR` / `JPY`），即上列原幣金額的幣別 |
| `assetType` | string | 資產類型（`STOCK` / `CRYPTO` / `FX` / `BOND`） |
| `sector` | string \| null | 產業分類；`null` 代表未分類，由前端顯示「未分類」（後端不回在地化字串） |
| `baseCurrency` | string | 使用者基準幣；`marketValueInBase` 的幣別 |
| `marketValueInBase` | number \| null | `marketValue` 折算為 `baseCurrency` 的值；**換不到匯率時為 `null`** |
| `previousClose` | number \| null | 前收（原幣）：該標的所屬市場交易日起點之前的最後一筆成交；沒有前收或查無行情時為 `null` |
| `dayPricePnl` | number \| null | 單檔今日價格損益（原幣），規則見〈今日價格損益〉；計算所需價格缺失時為 `null` |
| `dayPricePnlRatio` | number \| null | `dayPricePnl / dayPnlBasis`，**比值而非百分比**；分母為 0 或損益為 `null` 時為 `null` |
| `dayPnlBasis` | number \| null | 今日損益比率的分母（原幣）= 前收時市值 + 今日買入成本；供 summary 折算後計算總額比率，bearer client 可忽略 |

**估值用的最新價**：取自 market-data（Redis 即時快取 → `market_prices` 最新一列）。查無任何行情時退回平均成本估值——此時 `marketPrice` 等於 `avgCost`、`unrealizedPnl` 為 0、`priceTime` 等於 `lastUpdated`。前端若要區分「沒有行情」可比對這兩個時間。

## GET /api/v1/portfolio/summary — 投資組合摘要

權限：`PORTFOLIO_VIEW`。回應：`PortfolioSummaryDto`。

> ⚠️ **2026-10-24 單位語意變更（Phase 04.1 D-05）**：所有金額欄位改以 `baseCurrency`（使用者基準幣）表示；先前版本是未折算的混幣加總（USD 與 TWD 直接相加，錯誤）。欄位名不變，bearer client 與前端須改讀 `baseCurrency` 判斷單位。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `totalMarketValue` | number | 所有持倉 marketValue 逐筆折算為基準幣後加總（換不到匯率的持倉不計入） |
| `totalCostBasis` | number | 所有持倉 costBasis 逐筆折算為基準幣後加總（同上） |
| `realizedPnl` | number | **含已平倉部位**；逐資產依各自幣別折算為基準幣後加總（不是 SQL 跨幣別 sum） |
| `unrealizedPnl` | number | totalMarketValue − totalCostBasis（基準幣） |
| `totalPnl` | number | realizedPnl + unrealizedPnl（基準幣） |
| `roi` | number | totalPnl / totalCostBasis，語意不變（兩者同為基準幣；totalCostBasis 為 0 時為 0）。**不含現金** |
| `holdingCount` | number | 持有數量大於 0 的標的數（含換不到匯率而未計入總額者） |
| `baseCurrency` | string | 上列金額欄位所使用的基準幣（`USD` / `TWD` / `EUR` / `JPY`） |
| `fxExcludedCount` | number | 持有數量大於 0、但換不到匯率而**未計入**總額的持倉檔數 |
| `unconvertedCurrencies` | string[] | 換不到匯率的幣別代碼（持倉、已實現損益、今日損益或現金），字母排序、去重；全部可換算時為空陣列 |
| `dayPricePnl` | number \| null | 今日價格損益總額（基準幣、以目前匯率折算）；`null` = 缺資料，`0` = 真實的零，規則見〈今日價格損益〉 |
| `dayPricePnlRatio` | number \| null | 今日價格損益比率 = Σ損益 / Σ前收時市值與買入成本（皆基準幣），**比值而非百分比**；總額為 `null` 或分母為 0 時為 `null` |
| `dayPnlExcludedCount` | number | 因計算所需價格缺失而未計入今日損益的檔數；換不到匯率者改計入 `fxExcludedCount` / `unconvertedCurrencies`，不在此數內 |
| `cash` | number | 可用現金合計（基準幣）：各幣別現金餘額以目前匯率折算後加總；**可為負**；不含在 `roi` 內，規則見〈可用現金〉 |
| `totalEquity` | number | 總資產 = `totalMarketValue + cash`（基準幣） |
| `cashBalances` | `CashBalanceDto[]` | 各幣別的原幣現金餘額，依 `CurrencyCode` 宣告順序（`USD`、`TWD`、`EUR`、`JPY`），只含有過入出金或交易的幣別；沒有任何紀錄時為空陣列（不是 `null`） |
| `allocation` | `AllocationSliceDto[]` | 類別配置（donut）：資產類型分塊加上正現金，規則見〈資產配置與產業分組〉 |
| `sectorAllocation` | `SectorAllocationDto[]` | 產業配置：依 `sector` 原值分組（不含現金），規則見〈資產配置與產業分組〉 |

### 今日價格損益

- **只含價格損益**：不含匯差、不扣手續費；總額以**目前匯率**折算各檔的原幣損益後加總（`dayPricePnl` 與 `dayPricePnlRatio` 皆然）。
- 「今日」依**各檔所屬市場的交易日**（`TradingDay`：股票依交易所時區午夜、外匯紐約 17:00、加密 UTC 00:00，與報價卡同一條規則），不是統一的日曆日。
- 單檔損益 = `Q1 × 最新價 − Q0 × 前收 − Σ今日買入(數量 × 價) + Σ今日賣出(數量 × 價)`，其中 `Q1` 為目前持有數量、`Q0` 為交易日起點持有數量（由 `Q1` 與今日成交反推，不需要日級快照表）。
- **前收只在 `Q0 > 0` 時必要**：今天才首次買入的標的（`Q0 = 0`）沒有前收也算得出來，損益是以最新價相對成交價；`Q0 > 0` 而無前收，或查無最新價時，該檔 `dayPricePnl` 為 `null`，且**不計入**總額（計入 `dayPnlExcludedCount`）。
- **今天賣光的部位計入總額**：它不在持倉列表，但其損益仍屬於今天，否則當日停損的損失會在總額裡憑空消失。
- 已知限制：今天賣光的部位只在 `last_updated` 於近 7 天內的平倉列中尋找（涵蓋週末與連假）；某市場連續 7 天以上沒有任何成交時，邊界外的賣出不計入。
- **總額的 `null` 與 `0`**：沒有任何候選（無持倉且今日無成交）→ `0`；有候選但全部不計入 → `null`（缺資料，不塞 0）；否則為已計入者的總和，其餘檔數見 `dayPnlExcludedCount`。

### 可用現金

- 現金是**記帳推導值，不是券商帳戶**：`cashBalances[currency]` = 該幣別入出金淨額（沖銷是反向分錄，自然抵銷）＋ 該幣別標的的全部交易現金流（賣出 = `數量 × 價 − 手續費`，買入 = `−(數量 × 價 + 手續費)`）；幣別取自標的的交易幣別。系統不存任何餘額欄位。
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
| `occurredAt` | string（ISO-8601） | 否 | 省略視為伺服器現在；不得晚於伺服器時間 5 分鐘以上 |
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
| 400 | `VALIDATION_FAILED` | `error.fields` 的鍵：`Idempotency-Key`、`type`（`unsupported type`）、`currency`（`unsupported currency`）、`amount`、`note`、`occurredAt`（`must not be in the future`）、`reversalOfId`（`invalid format` / `must mirror the original entry`；超過 36 字元為 bean validation 訊息） |
| 400 | `VALIDATION_FAILED` | body 不是合法 JSON 或欄位型別錯；訊息固定、無 `error.fields` |
| 404 | `CASH_ENTRY_NOT_FOUND` | 沖銷對象不存在，或屬於他人（兩者無法區分，body 不含該 UUID） |
| 409 | `CASH_IDEMPOTENCY_KEY_REUSED` | 同一把鍵搭配不同 payload |
| 409 | `CASH_ENTRY_ALREADY_REVERSED` | 沖銷對象已被沖銷（含併發落敗：另一把鍵搶先沖銷） |
| 409 | `CASH_ENTRY_NOT_REVERSIBLE` | 沖銷對象本身是沖銷分錄 |
| 403 | `AUTH_FORBIDDEN` | 缺 `TRADE_EXECUTE` 權限 |
| 429 | `AUTH_RATE_LIMITED` | 一般 API 限流 |

錯誤訊息為靜態字串，不回射使用者輸入（含冪等鍵、備註與 UUID）。前端依 `code` 分派文案，這些 code 字面不會更名。

### 稽核

一般入出金記為 `cash_entry_create`，沖銷記為 `cash_entry_reverse`；成功的 target 為 `cash_entry:<id>`、result 為 `success`，失敗的 target 為 `cash_entry`、result 為 `failure:<code>`。

## GET /api/v1/portfolio/cash-entries — 入出金紀錄

權限：`PORTFOLIO_VIEW`。只含登入者自己的分錄。

| 參數 | 預設 | 規則 |
|---|---|---|
| `page` | `0` | 夾限至 0..10000；非數字 → 400 `VALIDATION_FAILED`（`page must be a number`，無 `fields`） |
| `size` | `20` | 夾限至 1..100；非數字 → 400 `VALIDATION_FAILED`（`size must be a number`，無 `fields`） |

排序固定為 `occurredAt` 新到舊、同時間以 `id` 新到舊，沒有排序或篩選參數。回應：`PageResponse<CashEntryDto>`；沖銷分錄帶 `reversalOfId` 與 `reversalOfOccurredAt`，被沖銷的原分錄 `reversed = true`。

## 快取與一致性

- 持倉與摘要有 60 秒 Redis 快取，鍵為 `portfolio:valuation:v2:{userId}:{assetId}`（存**原幣**估值，`baseCurrency` / `marketValueInBase` 於回應時才補上）與 `portfolio:summary:v2:{userId}:{baseCurrency}`。
- summary 依基準幣分鍵：使用者切換基準幣（`PATCH /api/v1/me/preferences`）後，下一次讀取直接 miss、立即拿到新幣別的值，不必等 60 秒。
- 成交後在交易 **commit 之後**失效持倉鍵與 `USD` / `TWD` / `EUR` / `JPY` 四把 summary 鍵，所以成交後立即重讀一定看得到新交易。
- 入出金分錄（含沖銷）在寫入 **commit 之後**只失效上述四把 summary 鍵，**不影響持倉鍵**（分錄不改變持倉估值）；現金項因此留在 summary 快取之內，入金後立即重讀就看得到新的 `cash`。冪等命中與被拒絕的請求（資料未變）不失效。
- 殘餘競態（與成交路徑相同、已接受）：讀者若在 commit 前讀了資料庫、於失效之後才寫回快取，舊值最長停留 60 秒。
- 鍵中的 `v2` 讓部署瞬間新舊程式不會讀到對方形狀的 JSON；舊鍵 60 秒內自然過期。
- **摘要 JSON 在 Phase 04.1 內陸續追加欄位（今日損益、現金、配置）但鍵維持 `v2`**：Phase 04.1 作為一次部署上線，`v2` 鍵在此之前不存在，所以不會有舊形狀的 `v2` JSON。只有「部署過 04.1 中途版本的共用環境」才會有最多 60 秒的視窗，期間命中的舊 `v2` summary 缺新欄位（Jackson 以 `null` 讀入，`allocation` / `sectorAllocation` / `cashBalances` 可能是 `null` 而非空陣列），60 秒 TTL 到期後自動恢復。bearer client 對這些欄位仍應容錯 `null`。
- 前端成交後應重讀 summary、holdings 與 trades（Phase 4 post-trade refetch）。

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

- 股利與股數調整（`transactions.type` 擴充、拆股）、Watchlist —— 見 Phase 04.2。
- 匯兌損益、帳戶層級報酬率 —— Deferred（今日損益只含價格損益；`roi` 不含現金，匯率目前為靜態 seed）。
