---
created: 2026-11-07
title: Phase 5 範圍裁決(Phase 5 discuss 的輸入)
area: verification
target: Phase 5
source: Yuan 2026-11-07 待辦裁決第 1、2、3C-2、3C-8 題;quick-261107-1j4
---

> **Phase 5 discuss 的輸入。以下各點 Yuan 已裁決,勿重議**;discuss 只需決定實作細節與切 plan 的方式。
> Phase 5 = Cross-Repo Browser Flow Verification & Contract Hardening(VER-01 / 02 / 03,見 ROADMAP.md)。

## 範圍(Yuan 2026-11-07 第 2 題)

1. **SC1 / SC2 對照稽核補缺**:把 ROADMAP Phase 5 Success Criteria 1(後端 Maven 測試覆蓋 auth cookie、CSRF、CORS、refresh / logout、401 / 403 信封與交易冪等)、2(前端 Vitest / type-check / build 覆蓋 API client、auth store、runtime mode、portfolio / trading adapter)對照現有測試,逐條列出已涵蓋 / 缺口,只補缺口。
2. **補瀏覽器測試**(真後端,跑在 CI 的 browser E2E):
   - **SC3**:login → `/me` → portfolio reads → create manual trade → refetch → logout,一條**單一串連**的 journey(不是分散在多個 spec 的片段)。
   - **SC4**:auth cookie 為 httpOnly、CSRF token / header 正確、protected request 帶 credentials,以及**登出後 `GET /api/v1/me` 回 401**。
   - **SC5**:API mode 整合失敗時顯示錯誤 / 重試狀態,不被 mock data 掩蓋。
3. **`it-shared-containers` 併入 Phase 5**(見 `2026-09-27-it-shared-containers.md`,frontmatter 已改 `target: Phase 5`)。
4. **`@extended` 每次 PR 一起跑**(目前 `run-e2e.sh` 無參數預設只跑 `@smoke`,`@extended` 需 `--grep @extended`)。
5. **金額精度**:前端引入 decimal 套件;金額 / 數量 / 價格欄位在請求與回應都改字串;service 層轉 decimal。細節見 `2026-11-06-cash-amount-string-serialization.md`〈決定〉。

6. **quick-261107-1j4 留下的觀察一併處理**(Yuan 2026-11-07 追加裁決,來源見 `.planning/quick/261107-1j4-small-fixes-batch-b-04-2-review-closeout/261107-1j4-SUMMARY.md`〈需 Yuan 處理 / 決定〉):
   - 交易 `executedAt`「晚於現在」的錯誤補 `fields.executedAt`(前端顯示欄位錯誤,與 1900 下限一致)。
   - 觀察清單名稱 DB CHECK 擴充到不換行空白(新 migration;目前只擋在服務層,04.2 IN-04)。
   - 紅漲綠跌模式下 Ops 失敗狀態燈不應變綠(失敗 / 錯誤語意比照 `--success` 另立固定色);寫死的 `rgba` 字面色底(如 `.pill.buy`)改用會跟著對調的 token。
   - CashEntryDialog / OrderTicket 的「未來時間」判斷改用 `useNow`,隨時間更新(與 04.2 IN-09 同類)。

7. **2026-11-07 文件全面更新(map-codebase + docs-update)發現的契約 / 設定一致性問題**(Yuan 裁決併入 Phase 5;細節見 `docs/testing/overview.md`、`docs/api/reference.md`、`docs/configuration/overview.md`、`docs/guides/getting-started.md`):
   - `stock-start` 裡 7 個舊的 `*E2ETest`(`Ws*E2ETest` ×5、`MarketRestApiE2ETest`、`BackfillAdminFlowE2ETest`)符合 Surefire `*Test.java`,`./mvnw test` 會起 Testcontainers;又不符合 `-Pe2e` 的 include,E2E job 不會跑它們。改名為 `*E2E.java`(或調整 include / exclude)並確認 CI 各 job 的歸屬。
   - `ai-docs/architecture.md` 寫「Spring Data JDBC」,實際為手寫 `Jdbc*Repository` + `JdbcClient`(market-data 批次寫入用 `NamedParameterJdbcTemplate`),規範檔用詞更正。
   - `ai-docs/browser-auth-contract.md` 補:瀏覽器已帶有效 `stock_access` cookie 時,login / register 會被視為 cookie 認證請求而要求 CSRF(依 `BrowserCsrfFilter` 實際行為)。
   - 註解與程式不符:`application-demo.yaml` 檔頭沒列 `STOCK_AUTH_COOKIE_SECURE`(demo 預設 `true`);`application.yaml` 註解的 `JwkKeyConverter` 指令與 javadoc / `JwtService` 錯誤訊息(`PropertiesLauncher` + `-Dloader.main`)不一致。
   - 前端 Vite 代理指向 `localhost:8080`,後端 `dev` profile 預設 `11180`(目前靠 `SERVER_PORT=8080` 對齊);決定要對齊哪一邊。
   - access cookie 預設 `PT15M`、access JWT 預設 `PT30M`,兩個存活時間不一致;確認是否刻意,否則對齊並寫進契約。

## 維持現狀(不排工作)

- **DB 計數**(3C-8):分頁的 `count(*)` 與觀察清單 100 檔上限的 count 維持現狀。Yuan 追問「直接呼叫 DB 計數對服務的影響」,影響分析已在 2026-11-07 討論中回答,裁決為不改。

## 不在 Phase 5(排程見各 todo)

- 五個大型功能(Yuan 2026-11-07 第 1 題)都不插隊:先 Phase 5 → 收 v1.0 → 新 milestone。
  - 新 milestone 第一個 phase:帳戶層級報酬率(`2026-09-28-account-level-return.md`)。
  - 「真實行情與參考資料來源」主題:匯率歷史(`2026-09-28-fx-pnl-and-rate-history.md`)、K 線還原(`2026-09-28-kline-adjusted-prices.md`)、資產主檔(`2026-09-28-asset-master-management.md`)。
  - 資產分類剩 Analytics 產業分布,隨 Analytics API 化(`2026-07-19-backend-asset-classification.md`)。
- 帳號與安全強化(`2026-11-07-account-and-security-hardening.md`)、Ops / AI Access 後端(`2026-11-07-ops-and-ai-access-backend.md`):backlog。

## 下一步順序(Yuan 2026-11-07,3C-2)

1. quick-261107-1j4(程式 + 文件 + todo 紀錄;本檔即其產物)
2. `/gsd-map-codebase`(重產 `.planning/codebase/*`)
3. `/gsd-docs-update`(重產後端 `AGENTS.md`)
4. Phase 5 discuss → plan → execute
