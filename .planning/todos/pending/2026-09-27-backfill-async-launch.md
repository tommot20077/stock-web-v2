---
created: 2026-09-27
title: POST /backfill 改為非同步啟動(回 202 + jobExecutionId)
area: backend-marketdata
files:
  - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java
  - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobLauncher.java
---

## Problem

2026-09-02 效能審查 MED-8:Spring Boot 預設 `JobLauncher` 是同步的,`POST /api/v1/market/backfill`
回應時 job 已跑完,整段期間佔住 Tomcat 執行緒(上限 90 天範圍)。mock provider 下只要幾秒,
換真實 provider 會跑數分鐘,撞 proxy / LB 逾時。已有 `GET /{jobExecutionId}` 狀態端點。

## 需要 Yuan 決定

改成 `TaskExecutorJobLauncher` + 有界 executor、回 **202** + `jobExecutionId` 屬 API 契約變更
(狀態碼與回應語意改變,judgment §9)。前端目前沒有呼叫 backfill(admin 功能)。

## Verification

- 慢 provider 下 `POST` 在 1 秒內回 202,之後以 `GET /{id}` 看到 COMPLETED。
