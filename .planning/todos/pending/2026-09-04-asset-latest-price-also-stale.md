---
created: 2026-09-04
title: asset 模組的 latestPrice 也讀死表 asset_latest_prices,報價卡顯示種子價
area: backend-marketdata
files:
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/repository/AssetRepository.java
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetDto.java
---

## Problem

`asset_latest_prices` 這張表**只有 `V2__foundation_seed_assets.sql` 寫過一次**,全 repo 沒有任何程式更新它
(2026-09-02 架構審查 H-1;2026-09-04 再次 grep 確認)。

trading 模組的持倉估值已經改走 `MarketDataFacade`(PR「WS 廣播 listener…」同批的第二條 P0),
但 **asset 模組仍在讀它**:`AssetRepository` 以 `left join asset_latest_prices` 填 `AssetDto` 的
`latestPrice` / `change` / `changePercent` / `volumeText` / `high` / `low`。

使用者可見的後果:order ticket 的報價卡走 `GET /api/v1/assets`,顯示的六個數字全部是種子價,
不會隨行情變動。2026-09-04 走查時 AAPL 一直顯示 `218.40`,就是 `V2` 的 seed 值。

## 已經備好的材料

`MarketDataFacade.findLatestPrice(assetId)` 已存在(`stock-infrastructure`),
實作 `MarketDataFacadeImpl` 走 Redis `market:latest:{assetId}` → `market_prices`,有單元測試。
asset 模組只要注入它即可,不需再寫一次取價邏輯。

## Scope

1. `AssetRepository` 的 SQL 移除 `asset_latest_prices` join,只回傳 asset 本身的欄位。
2. asset service 以 `MarketDataFacade` 補上 `latestPrice` / `priceTime`。
3. `change` / `changePercent` / `high` / `low` / `volumeText` **沒有現成來源** —— 這幾個需要日內
   開盤價與高低點,目前 `market_prices` 只有逐筆 tick。決定方式:
   - 由 `market_prices` 當日資料即時聚合(成本高,每次請求掃當日 tick),或
   - 走 TimescaleDB continuous aggregate(對齊 architecture.md 的預計算設計),或
   - 誠實回 null 並讓前端隱藏該欄位(最小改動,但前端要配合)。
   **這一條要先裁決再實作**,不要順手挑一個。
4. 決定 `asset_latest_prices` 的去留:確認沒有其他讀取者後,以新 migration DROP(遵守
   flyway-convention:不得修改已套用的 migration)。

## Verification

- `GET /api/v1/assets?query=AAPL` 的 `latestPrice` 隨 mock ingestor 推進而變動(不再固定 218.40)。
- 沒有任何模組的 SQL 再出現 `asset_latest_prices`(grep 為零,或只剩 migration 檔)。
- `./mvnw test` 與 `./mvnw -pl stock-start -am verify` 全綠。
