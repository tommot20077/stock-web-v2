# Watchlist API Contract

> 後端是權威（judgment §4）。本檔記錄 `stock-module-asset` 的觀察清單（watchlist）對前端公開的契約：端點、DTO 欄位、上限、錯誤碼與 `fields` key。
> 認證、cookie、CSRF、401/403 信封見 [browser-auth-contract.md](browser-auth-contract.md)；交易、持倉、股利與活動列表見 [trading-portfolio-contract.md](trading-portfolio-contract.md)。
>
> 程式碼是事實來源。與本檔衝突時以程式碼為準，並回頭修本檔。
> 權威檔案（asset 模組）：`WatchlistController`、`WatchlistDto`、`AssetDto`、`AssetQuoteMapper`、`WatchlistService`、`WatchlistRepository`、`ErrorCode`。
>
> ℹ️ **已實作（Phase 04.2）**：本檔於 2026-11-01（04.2-02，Yuan 於決策閘門裁決 option-recommended）凍結，作為前後端平行實作的共同依據；04.2-11 / 04.2-12 已合入（後端 PR #51、前端 PR #19），目前**程式碼是事實來源**——程式與本檔偏離時以程式為準，回頭修本檔並回報。

## 共通

- 所有回應都包在 `ApiResponse<T>`：`{ success, data, error, meta }`，`meta.traceId` 恆存在。
- 全部端點 `@PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")`（`USER` 角色已具備）；缺權限 → 403 `AUTH_FORBIDDEN`。
- 資料表 `watchlists` / `watchlist_items` 歸 asset 模組；清單只屬於建立者，所有查詢以 `(uuid, user_id)` 解析。
- **路徑一律用 UUID，不放 symbol**：FX 代號含 `/`（`USD/TWD`、`EUR/USD`、`USD/JPY`），放進路徑會被 servlet 容器拒絕或誤切。加入項目時以 body `{symbol}` 指定（前端 Markets / Chart 只有 symbol）；移除與排序用 `AssetDto.uuid`。
- **不需要 `Idempotency-Key`**：加入 / 移除 / 改名 / 重排皆天然冪等，建立清單由 `(user_id, name)` 唯一約束擋雙擊。judgment §5 的冪等要求針對交易帳本。
- **CSRF**：`POST`、`PUT`、`PATCH`、`DELETE` 皆為 unsafe method，cookie 路徑必須帶 `X-XSRF-TOKEN`（與 `XSRF-TOKEN` cookie 同值），否則 403 `AUTH_CSRF_TOKEN_INVALID`（見 browser-auth-contract〈CSRF〉）；前端 `apiClient.ts` 自動處理。bearer client 不適用 CSRF。
- 一般 API 限流：已登入每位使用者 100 次 / 分鐘，超過回 429 `AUTH_RATE_LIMITED`。

## 上限與併發保證

| 項目 | 上限 | 超過時 | 併發保證 |
|---|---|---|---|
| 每位使用者的清單數 | 20 | 400 `VALIDATION_FAILED`，`fields.watchlists` | 建立前先確保預設清單存在，再 `SELECT … FROM watchlists WHERE user_id = :userId FOR UPDATE` 鎖住該使用者全部清單列 → **取得鎖之後另起**的 `countByUser` 查詢 → insert（count 不能沿用鎖查詢本身的結果：READ COMMITTED 下鎖查詢的快照在等鎖前取得，看不到前一個持鎖交易剛 commit 的列；見 `WatchlistService#create` 註解）；因至少有一列可鎖，並發建立會序列化，上限是硬的 |
| 每份清單的項目數 | 100 | 400 `VALIDATION_FAILED`，`fields.items` | 加入前 `SELECT … FROM watchlists WHERE uuid = :id AND user_id = :userId FOR UPDATE` 鎖住清單列 → **取得鎖之後另起**的 `countItems` 查詢 → insert（理由同上；見 `WatchlistService#addItem` 註解）；同清單的並發加入序列化 |
| 清單名稱 | trim 後 1–30 字；同使用者唯一 | 長度不符 400 `fields.name`；重名 409 `DUPLICATE_RESOURCE`，`fields.name` | `(user_id, name)` 唯一約束 |

- **預設清單**：使用者 0 個清單時，`GET /api/v1/watchlists` 先建立「我的自選」：`INSERT … ON CONFLICT (user_id, name) DO NOTHING` 後重讀——兩個並發的首次 GET 只會留一列，不需 advisory lock。刪掉全部清單後，下一次 GET 會重建（星號選擇器需要至少一個清單）。
- 同一資產可同時在多份清單；同一清單內由主鍵 `(watchlist_id, asset_id)` 保證唯一。
- 清單之間的順序：`created_at, id`。清單內順序：整數 `position`，新項目排在**尾端**（`max + 1`），重排為整份重寫。

## 端點

| 方法 | 路徑 | Body | 回應 |
|---|---|---|---|
| `GET` | `/api/v1/watchlists` | — | `WatchlistDto[]`（依 `created_at, id`；0 個清單時先建「我的自選」） |
| `POST` | `/api/v1/watchlists` | `{ "name": string }` | `WatchlistDto` |
| `PATCH` | `/api/v1/watchlists/{id}` | `{ "name": string }` | `WatchlistDto` |
| `DELETE` | `/api/v1/watchlists/{id}` | — | `ApiResponse<Void>`（項目 `ON DELETE CASCADE`） |
| `POST` | `/api/v1/watchlists/{id}/items` | `{ "symbol": string }` | `WatchlistDto` |
| `DELETE` | `/api/v1/watchlists/{id}/items/{assetId}` | — | `WatchlistDto` |
| `PUT` | `/api/v1/watchlists/{id}/items/order` | `{ "assetIds": string[] }` | `WatchlistDto` |

`{id}` 為清單 UUID；`{assetId}` 為 `AssetDto.uuid`。

### POST / PATCH — 建立與改名

- `name` trim 後 1–30 字，否則 400 `VALIDATION_FAILED`，`fields.name`。
- 同使用者已有**另一份**同名清單 → 409 `DUPLICATE_RESOURCE`，`fields.name`。
- `POST` 時已有 20 個清單 → 400 `VALIDATION_FAILED`，`fields.watchlists`。

### POST /{id}/items — 加入項目

- `symbol`：**任何 active 資產**，含 FX 與債券，**不要求 tradeable**（D-18）；前後空白去除、**大小寫不敏感**（後端以 `toUpperCase(Locale.ROOT)` 正規化，`aapl` 等同 `AAPL`，與股利 / 股數調整 / 活動列表一致）；不存在或 inactive → 404 `ASSET_NOT_FOUND`，`fields.symbol`。
- 已在清單中 → 原樣回傳該清單（冪等，不報錯、不改順序）。
- 清單已有 100 檔 → 400 `VALIDATION_FAILED`，`fields.items`。
- 新項目排在尾端。

### DELETE /{id}/items/{assetId} — 移除項目

- 項目不在清單中 → 原樣回傳該清單（冪等）。

### PUT /{id}/items/order — 整份重排

- `assetIds` 為清單目前**全部**項目 UUID 的新順序；後端於一次交易內依序重寫 `position` 為 1..n。
- 送來的集合與目前集合不同（多、少、重複，或其間被另一分頁加入 / 移除）→ 409 `WATCHLIST_ORDER_CONFLICT`，前端應重讀清單後再讓使用者重試。

### 擁有權

- 清單不存在或屬於他人 → 一律 404 `WATCHLIST_NOT_FOUND`（無法區分，judgment §6，不洩漏存在性）；適用於所有帶 `{id}` 的端點。

## 回應 — `WatchlistDto`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | string | 清單 UUID |
| `name` | string | 清單名稱（trim 後） |
| `items` | `AssetDto[]` | 依 `position` 排序的項目 |

`AssetDto` 與 `GET /api/v1/assets` **逐欄同形**（同一個 `AssetQuoteMapper`，自 `AssetQueryService` 抽出）：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `uuid` | string | 資產 UUID（移除 / 排序用） |
| `symbol` | string | |
| `name` | string | |
| `assetType` | string | `STOCK` / `CRYPTO` / `FX` / `BOND` |
| `market` | string | |
| `currency` | string | |
| `sector` | string \| null | |
| `tradeable` | boolean | 觀察清單可含不可交易的資產（FX / 債券），前端據此決定是否顯示下單入口 |
| `latestPrice` | number \| null | 報價欄位規則見 trading-portfolio-contract〈GET /api/v1/assets — 標的報價欄位〉 |
| `change` / `changePercent` | number \| null | |
| `volumeText` | string \| null | |
| `high` / `low` | number \| null | |

- 報價每次回應以一次 `findDailyQuotes`（distinct assetId 批次）取得；**沒有任何行情的資產六個報價欄位皆為 `null`**（不塞預設價），前端顯示「—」。
- 即時價更新走 WebSocket `tick.{symbol}`（前端 WS client，見 `ai-docs/security.md` WS 章節），不在本 REST 契約內。

## 錯誤

| HTTP | code | `fields` | 情境 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | `name` | 名稱 trim 後為空或超過 30 字 |
| 400 | `VALIDATION_FAILED` | `watchlists` | 第 21 個清單 |
| 400 | `VALIDATION_FAILED` | `items` | 清單第 101 檔 |
| 400 | `VALIDATION_FAILED` | `symbol` / `assetIds` | body 欄位缺漏或格式錯 |
| 400 | `VALIDATION_FAILED` | — | body 不是合法 JSON 或欄位型別錯；訊息固定 |
| 404 | `WATCHLIST_NOT_FOUND` | — | 清單不存在或屬於他人 |
| 404 | `ASSET_NOT_FOUND` | `symbol` | 加入的資產不存在或 inactive |
| 409 | `DUPLICATE_RESOURCE` | `name` | 同使用者重名 |
| 409 | `WATCHLIST_ORDER_CONFLICT` | — | 重排集合與目前項目不符 |
| 403 | `AUTH_FORBIDDEN` | — | 缺 `WATCHLIST_MANAGE` 權限 |
| 403 | `AUTH_CSRF_TOKEN_INVALID` | — | cookie 路徑的 unsafe method 缺少或送錯 CSRF header |
| 429 | `AUTH_RATE_LIMITED` | — | 一般 API 限流 |

錯誤訊息為靜態英文字串（`Watchlist not found`、`Watchlist items changed; reload and retry`），不回射使用者輸入（含清單名稱、symbol 與 UUID）。前端依 `code` 分派文案，這些 code 字面不會更名。

## 變更紀錄

- 2026-11-06（quick-261106-fbs，文件掃描）：檔首凍結提示改為「已實作、程式為事實來源」；上限表補明 count 必須是取得列鎖之後另起的查詢（04.2 G2）。
- 2026-11-06（quick-261106-fbs）：加入項目的 `symbol` 改為大小寫不敏感（strip 後 `toUpperCase(Locale.ROOT)`），修正小寫 symbol 回 404 `ASSET_NOT_FOUND`（04.2 REVIEW WR-02）。
- 2026-11-01（Phase 04.2，04.2-02）：初版凍結。七個端點、上限 20 / 100 與列鎖併發保證、`fields` key（`name` / `watchlists` / `items` / `symbol`）、錯誤碼 `WATCHLIST_NOT_FOUND` / `WATCHLIST_ORDER_CONFLICT`。
