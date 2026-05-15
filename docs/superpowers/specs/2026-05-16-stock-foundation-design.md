# stock-web-v2 Foundation-first 後端設計

日期：2026-05-16

## 背景

`stock-web-v2` 後端目前仍接近 Spring Boot 初始骨架，但 repo 已有完整架構討論文件。前端雛型 `D:\end\workspace\vue\stock-v2` 已有 `Overview`、`Markets`、`Watchlist`、`Trades`、`Positions` 等核心頁面，也有後續的 `Alerts`、`Backtest`、`Settings`、`Ops`、AI/MCP access 概念。

本次決策採用 **Foundation-first**：先完成後續業務模組共用的後端地基，再進入投資紀錄 MVP。此階段不急著完整支撐前端核心五頁，而是把 module boundary、API envelope、Auth、migration、Redis、Actuator、OpenAPI、測試基礎固定下來。

## 已確認的產品邊界

後續投資紀錄 MVP 的方向已確認，但不納入本 Foundation spec 實作：

- MVP 類型：投資紀錄 MVP。
- 交易性質：手動記帳，不接 broker，不送真實訂單。
- 價格來源：seed/manual latest price，不接外部行情。
- Auth：完整 Auth MVP，投資資料綁 user。
- 首版 UI 支撐目標：`Overview`、`Markets`、`Watchlist`、`Trades`、`Positions`。
- 現金：追蹤 cash balance，BUY 扣現金，SELL/DIVIDEND 加現金。
- 基礎設施：PostgreSQL/TimescaleDB + Redis + Flyway。
- 幣別：多幣別但匯率手動維護。
- 帳戶模型：`Portfolio + BrokerAccount/InvestmentAccount` 分層。
- BrokerAccount：帳戶分類 + 風控設定，不存 API key，不測連線。
- 交易類型：`BUY`、`SELL`、`DIVIDEND`、`CASH_DEPOSIT`、`CASH_WITHDRAWAL`。
- 成本法：加權平均成本；交易 append-only；holdings 可重算；未來預留 FIFO。
- 資產維護：seed + admin 維護。
- 列表 API：資產/交易分頁，positions/watchlists/overview 一次回。
- Alerts/Notifications：第一版不做。

## Foundation Scope

本階段實作下列地基能力：

- Maven multi-module 結構。
- `stock-common`：`ApiResponse<T>`、錯誤碼、例外、共用 DTO/enum。
- `stock-db-migration`：集中 Flyway migration。
- `stock-infrastructure`：Security、Redis、Facade/Event/Search 抽象、共用設定。
- `stock-module-user`：註冊、登入、登出、refresh token、目前使用者。
- `stock-module-asset`：seed asset/latest price/fx rate 查詢。
- `stock-start`：主要啟動器、SecurityConfig、ExceptionHandler、OpenAPI、Actuator。
- PostgreSQL/TimescaleDB + Redis 開發環境設定。
- Auth 基礎使用 Redis token version。
- 最小 smoke API：`/api/v1/me`、`/api/v1/assets`，用來驗證模組、DB、Redis、安全與 response contract。

本階段不做完整 portfolio、broker account、交易記帳、持倉計算、行情 WebSocket、alerts、backtest、AI/MCP access。

## Module Boundary

Foundation-first 階段建立以下模組：

```text
stock-web-v2
├─ stock-common
├─ stock-db-migration
├─ stock-infrastructure
├─ stock-module-user
├─ stock-module-asset
└─ stock-start
```

暫不建立 `stock-module-trading`、`stock-module-market-data` 空模組。這些模組等投資紀錄 MVP 或行情階段開始時再建立，避免 module graph 在未被實際業務驗證前膨脹。

### stock-common

職責：

- `ApiResponse<T>`、`ApiError`、`ApiMeta`、`PageResponse<T>`、`EmptyResponse`。
- `ErrorCode` 與 HTTP status mapping。
- 基礎例外：`BusinessException`、`ResourceNotFoundException`、`DuplicateResourceException`。
- 共用 enum：`Role`、`Permission`、`UserStatus`、`AssetType`、`CurrencyCode`。

規則：

- 不依賴 Spring Web/Security 的具體配置。
- 不放 repository、service implementation、外部系統 client。

### stock-db-migration

職責：

- 集中放置 `db/migration` Flyway SQL。
- 第一批 schema 包含 users/auth、assets/latest price、fx_rates。

規則：

- 所有 migration 只放這個模組。
- `stock-start` 透過 classpath 載入 migration。
- 模組整合測試可用 test scope 引入 migration。

### stock-infrastructure

職責：

- Redis config。
- Security helper、JWT service、password encoder、audit logger。
- `EventPublisher`、`EventSubscriber`、`SearchService` interface。
- 共用 web/security/config 支援元件。

規則：

- 不放業務 repository。
- 不知道 user/asset/trading 的具體資料表細節。
- Facade/Event/Search 是穩定抽象，具體實作由模組提供。

### stock-module-user

職責：

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/me`
- user aggregate、auth service、refresh token service。

規則：

- 使用 BCrypt password hash。
- Access token 使用 JWT。
- Refresh token 是 opaque token，server-side 存 Redis。
- Redis 不可用時 Auth fail-closed，回 503。

### stock-module-asset

職責：

- seed asset 查詢。
- latest price 查詢。
- manual FX rate 查詢。
- admin 維護 API 只預留到後續投資紀錄 MVP，不在 Foundation 階段實作完整 CRUD。

規則：

- Foundation 階段不接外部行情。
- 價格為 latest snapshot，不建立 history/hypertable。

### stock-start

職責：

- `StockWebV2Application` 主要啟動器。
- 聚合 user/asset/infrastructure/db migration。
- `SecurityConfig`、`GlobalExceptionHandler`、OpenAPI、Actuator exposure。

規則：

- 只做組裝，不放業務邏輯。
- Controller 不跨模組呼叫 repository。
- Application service 可透過 Facade/Event 抽象跨模組溝通。

## API Response Contract

API 外層統一使用：

```java
ApiResponse<T>
```

資料形狀由 `T` 決定：

```java
ApiResponse<UserDto>
ApiResponse<List<PositionDto>>
ApiResponse<PageResponse<AssetDto>>
ApiResponse<OverviewDto>
ApiResponse<EmptyResponse>
```

`ApiResponse<T>` 欄位：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "meta": {
    "traceId": "string",
    "timestamp": "2026-05-16T10:30:00+08:00"
  }
}
```

錯誤時：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Validation failed",
    "fields": {
      "email": "must be a valid email"
    }
  },
  "meta": {
    "traceId": "string",
    "timestamp": "2026-05-16T10:30:00+08:00"
  }
}
```

規則：

- 成功與失敗都使用同一 envelope。
- 錯誤必須使用正確 HTTP status，不把錯誤包成 HTTP 200。
- `GlobalExceptionHandler` 統一輸出錯誤 response。
- validation error 帶 field errors。
- `traceId` 由 filter 產生並放入 MDC。
- 分頁使用 `ApiResponse<PageResponse<T>>`，不另外建立 `PageApiResponse`。

第一批錯誤碼：

- `VALIDATION_FAILED`
- `RESOURCE_NOT_FOUND`
- `AUTH_INVALID_CREDENTIALS`
- `AUTH_TOKEN_EXPIRED`
- `AUTH_REFRESH_TOKEN_INVALID`
- `AUTH_FORBIDDEN`
- `AUTH_REDIS_UNAVAILABLE`
- `DUPLICATE_RESOURCE`
- `INTERNAL_ERROR`

## Auth Contract

### Endpoints

| Method | Path | 說明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 註冊 |
| POST | `/api/v1/auth/login` | 登入並回 access token + refresh token |
| POST | `/api/v1/auth/refresh` | 用 refresh token 換新 access token |
| POST | `/api/v1/auth/logout` | 撤銷當前 refresh token |
| GET | `/api/v1/me` | 取得目前登入使用者 |

### Token Model

Access token：

- JWT。
- 短效。
- Claims 僅包含 `sub/userId`、`role`、`tokenVersion`、`iat`、`exp`。
- 不放 email、username 或其他 PII。

Refresh token：

- Opaque token。
- 存 Redis。
- Foundation 階段採簡化多裝置：允許多個 refresh token。
- Logout 只撤銷當前 refresh token。
- 「登出所有裝置」與 active session 管理留到後續。

Redis keys：

- `user:auth:{userId}`：保存 `tokenVersion`、`status`。
- `user:refresh:{opaqueToken}`：保存 `userId`、`tokenVersion`、`deviceInfo`、`createdAt`、`expiresAt`。
- `user:refresh:index:{userId}`：保存 refresh token reverse index，支援未來批次撤銷。

Redis 不可用：

- Auth filter、refresh、logout、ws-ticket 等依賴 token state 的流程 fail-closed。
- 回 HTTP 503 + `AUTH_REDIS_UNAVAILABLE`。

## Database / Migration Scope

第一批 migration 放在：

```text
stock-db-migration/src/main/resources/db/migration
```

### users

欄位：

- `id BIGSERIAL PRIMARY KEY`
- `uuid UUID NOT NULL`
- `email VARCHAR NOT NULL`
- `username VARCHAR NOT NULL`
- `password_hash VARCHAR NOT NULL`
- `role VARCHAR NOT NULL`
- `status VARCHAR NOT NULL`
- `token_version INT NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

約束：

- unique `uuid`
- unique `email`
- unique `username`

### assets

欄位：

- `id BIGSERIAL PRIMARY KEY`
- `uuid UUID NOT NULL`
- `symbol VARCHAR NOT NULL`
- `name VARCHAR NOT NULL`
- `asset_type VARCHAR NOT NULL`
- `market VARCHAR`
- `currency VARCHAR NOT NULL`
- `sector VARCHAR`
- `tradeable BOOLEAN NOT NULL`
- `active BOOLEAN NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

約束：

- unique `uuid`
- unique `symbol`

`asset_type` 首批值：

- `STOCK`
- `CRYPTO`
- `FX`
- `BOND`

### asset_latest_prices

欄位：

- `asset_id BIGINT PRIMARY KEY`
- `price NUMERIC NOT NULL`
- `change NUMERIC`
- `change_percent NUMERIC`
- `volume_text VARCHAR`
- `high NUMERIC`
- `low NUMERIC`
- `price_time TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

約束：

- FK `asset_id -> assets(id)`

### fx_rates

欄位：

- `id BIGSERIAL PRIMARY KEY`
- `base_currency VARCHAR NOT NULL`
- `quote_currency VARCHAR NOT NULL`
- `rate NUMERIC NOT NULL`
- `rate_time TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

約束：

- unique `(base_currency, quote_currency)`

### Seed Data

Dev seed 應包含前端 mock 目前使用的資產，例如：

- 股票：`AAPL`、`NVDA`、`TSLA`、`MSFT`、`2330.TW`、`GOOGL`、`AMZN`、`META`
- Crypto：`BTC`、`ETH`、`SOL`
- FX：`USD/TWD`、`EUR/USD`、`USD/JPY`
- Bond：`US10Y`、`US2Y`、`DE10Y`、`JP10Y`、`TW10Y`

Seed 同步建立 latest price 與必要 FX rate。正式 migration 不放固定帳號密碼；dev user 可透過 dev profile `ApplicationRunner` 或測試 fixture 建立。

### TimescaleDB

Foundation 階段只驗證 TimescaleDB extension 可用，不建立 hypertable。Market price history、K-line、continuous aggregate 留到 market-data 階段。

### Audit Log

Foundation 階段不建立 DB audit table。先使用 SLF4J `AUDIT` logger，交易與 admin 操作變多後再評估 DB audit。

## Testing / Verification

此 repo 的 `CLAUDE.md` 要求 TDD，Foundation implementation 必須遵守 Red -> Green -> Refactor。

### Common Unit Tests

- `ApiResponse` 成功與錯誤 factory。
- `ErrorCode` 對應 HTTP status。
- `PageResponse` shape。

### Module Slice Tests

- User register/login/refresh/logout service。
- Asset query service。
- validation error cases。
- duplicate email/username。
- Redis unavailable auth fail-closed。

### Start Integration Tests

- Spring context starts with all Foundation modules wired。
- Flyway migration runs。
- `register -> login -> me -> logout` flow。
- `/api/v1/assets` seed data returns expected symbols。
- exception handler output matches `ApiResponse` contract。

### Infrastructure Tests

- Testcontainers PostgreSQL/TimescaleDB。
- Testcontainers Redis。
- Spring Boot 4.x dependency spike：若 dependency/security/springdoc/testcontainers 出現 blocker，依既有 Go/No-Go 決策降級 Spring Boot 3.4.x LTS。

### Verification Commands

```powershell
.\mvnw test
.\mvnw -pl stock-start spring-boot:run
```

啟動後檢查：

- `GET /actuator/health`
- `GET /v3/api-docs`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /api/v1/me`
- `GET /api/v1/assets`

## Out of Scope

本 Foundation spec 不實作：

- Portfolio / BrokerAccount / Cash Balance。
- Trades、Holdings、加權平均成本計算。
- Watchlist persistence。
- Overview 聚合。
- Admin asset CRUD。
- 外部行情 provider。
- WebSocket。
- Alerts / Notifications。
- Backtest / Analytics。
- Broker API key storage。
- AI/MCP access。
- DB audit table。

## Acceptance Criteria

此設計完成後，下一階段 implementation plan 應能交付：

- Maven multi-module parent 可正常 build。
- Foundation modules dependency direction 清楚且可測。
- `ApiResponse<T>` 與 error contract 穩定。
- Flyway 集中 migration 可在 integration test 啟動時套用。
- PostgreSQL/TimescaleDB + Redis Testcontainers 測試可跑。
- Auth register/login/refresh/logout/me 可用。
- Redis token version fail-closed 行為可測。
- Asset seed query 可用。
- Actuator health 與 OpenAPI 可用。
- 沒有交易、持倉、行情等非 Foundation scope 的半成品。
