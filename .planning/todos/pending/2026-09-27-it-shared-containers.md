---
created: 2026-09-27
title: market-data IT 共用 Testcontainers(P2 #16 方案 B,backlog)
area: backend-test
---

## Context

P2 #16 已先做方案 A(PR #43:`*IT` 移到 failsafe,`./mvnw test` 不再起容器)。
方案 B 是讓同一 JVM 的 market-data IT 共用 Postgres / Kafka / Redis 容器,估計 `verify` 可再省約 60 秒
(5 次 Kafka 啟動變 1 次)。Yuan 於 2026-09-27 決定放 backlog。

## 風險(做之前要處理)

- 共用 DB:`MarketPriceRepositoryIT` 會清空 `market_prices`,K 線 IT 斷言筆數 → 每個類別改用獨立 database
  (同容器 `CREATE DATABASE` + Flyway)或獨立資產 / 時間窗。
- 共用 Kafka:上一個測試留在 topic 的訊息會被下一個測試的 consumer(`auto-offset-reset: earliest`)讀到 →
  每個類別用唯一 group id 或唯一 topic。
