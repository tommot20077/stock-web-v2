# Trading & Portfolio API Contract

> 後端是權威（judgment §4）。本檔記錄 `stock-module-trading` 對前端公開的契約：端點、DTO 欄位、驗證規則、錯誤碼與序列化格式。
> 認證、CSRF、401/403 信封見 [browser-auth-contract.md](browser-auth-contract.md)；本檔只談交易與持倉本身。
>
> 程式碼是事實來源。與本檔衝突時以程式碼為準，並回頭修本檔。
> 權威檔案：`TradingController`、`CreateTradeRequest`、`TradeDto`、`HoldingDto`、`PortfolioSummaryDto`、`TradingService`、`ErrorCode`。

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

**不含 `idempotencyKey`**——那是請求層的實作細節。

### 錯誤

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | body 欄位或 `Idempotency-Key` 不合法；看 `error.fields` |
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

| 欄位 | 型別 | 說明 |
|---|---|---|
| `assetId` | string | 標的 UUID |
| `symbol` | string | |
| `assetName` | string | |
| `totalQuantity` | number | |
| `avgCost` | number | 平均成本 |
| `costBasis` | number | totalQuantity × avgCost |
| `marketPrice` | number | 估值用的最新價（見下） |
| `marketValue` | number | totalQuantity × marketPrice |
| `realizedPnl` | number | 此標的的已實現損益 |
| `unrealizedPnl` | number | marketValue − costBasis |
| `roi` | number | unrealizedPnl / costBasis |
| `priceTime` | string | `marketPrice` 的時間戳 |
| `lastUpdated` | string | 持倉最後更新時間 |

**估值用的最新價**：取自 market-data（Redis 即時快取 → `market_prices` 最新一列）。查無任何行情時退回平均成本估值——此時 `marketPrice` 等於 `avgCost`、`unrealizedPnl` 為 0、`priceTime` 等於 `lastUpdated`。前端若要區分「沒有行情」可比對這兩個時間。

## GET /api/v1/portfolio/summary — 投資組合摘要

權限：`PORTFOLIO_VIEW`。回應：`PortfolioSummaryDto`。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `totalMarketValue` | number | 所有持倉 marketValue 加總 |
| `totalCostBasis` | number | 所有持倉 costBasis 加總 |
| `realizedPnl` | number | **含已平倉部位**的已實現損益加總 |
| `unrealizedPnl` | number | 所有持倉 unrealizedPnl 加總 |
| `totalPnl` | number | realizedPnl + unrealizedPnl |
| `roi` | number | totalPnl / totalCostBasis |
| `holdingCount` | number | 持有數量大於 0 的標的數 |

## 快取與一致性

- 持倉與摘要有 60 秒快取；成交後在交易 **commit 之後**失效，所以成交後立即重讀一定看得到新交易。
- 前端成交後應重讀 summary、holdings 與 trades（Phase 4 post-trade refetch）。

## 尚未提供

- 現金 / 帳戶餘額、日級損益、資產分類、股利交易類型——見 ROADMAP Phase 04.1 與 `.planning/todos/pending/`。
- 標的報價卡的 `latestPrice` 等欄位目前仍讀種子表，不隨行情更新——見 todo `2026-09-04-asset-latest-price-also-stale.md`。
