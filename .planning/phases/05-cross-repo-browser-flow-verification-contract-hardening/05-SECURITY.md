---
phase: "05"
slug: "cross-repo-browser-flow-verification-contract-hardening"
status: secured
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
block_on: high
created: "2026-11-12"
---

# Phase 05 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Register 取自 25 份 PLAN 的 `<threat_model>`(register_authored_at_plan_time: true);由 gsd-security-auditor 以 L1 深度逐條驗證(兩 repo 皆在 develop 合併 commit:後端 `87d5bdf`、前端 `06ab920`)。IT / E2E / browser E2E 證據為 CI run(見 05-COVERAGE-AUDIT.md)。

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| 瀏覽器 ↔ 後端 API | cookie 認證 + double-submit CSRF;回應信封 | auth cookie(HttpOnly)、CSRF token、金額 JSON 字串 |
| 前端 wire ↔ domain | service mapper 把 wire 字串轉 Decimal(strict) | 金額 / 數量 / 報價 |
| WS 行情 ↔ 前端 | tick 價格字串解析,無效即丟棄 | 報價 |
| CI(GitHub Actions)↔ 對方 repo | browser-e2e checkout 對方同名分支 | 原始碼(`contents: read`,無 secrets) |
| feature branch → develop(兩 repo) | 硬切需依序、同時窗合併 | 部署相容性 |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-05-01 | Information Disclosure | auth cookie / 前端儲存 | high | mitigate | J0 斷言 httpOnly、document.cookie / storage 不含 token、無 Bearer、token 不入 URL/body — **證據:** contract-journey.spec.ts:216-246、:95-101、:388-398;Browser E2E 129586950716 / 129268188684 / 130364643334 / 130360970606 | closed |
| T-05-02 | Tampering | unsafe 請求 CSRF | high | mitigate | J0 彙總斷言每個 POST/PATCH/PUT/DELETE 的 X-XSRF-TOKEN 等於同請求 XSRF-TOKEN cookie — **證據:** contract-journey.spec.ts:85-93、:294-299 | closed |
| T-05-SC(05-16) | Tampering | npm 安裝 big.js / @types/big.js | high | mitigate | Legitimacy Audit(05-RESEARCH:469-481)+ `--save-exact` + lockfile — **證據:** package.json:18,26 精確版本;package-lock.json:965-971、:1714-1724 sha512、無 install script;CI `npm ci` | closed |
| T-05-SC(05-13) | Tampering | Maven `tools.jackson.core:jackson-databind` | low | accept | BOM 管理,Jackson 3 phase 前已在依賴圖 — **證據:** stock-infrastructure/pom.xml | closed |
| T-05-SC(其餘 23 plan) | Tampering | npm / Maven 安裝 | high | accept | 該 plan 無新依賴 — **證據:** package.json / pom diff 無新增 | closed |
| T-05-03 | Repudiation | 六頁錯誤態 | medium | mitigate | SC5 哨兵斷言(限定區塊)證明失敗時不出現 mock 值;mockSentinels.test 防哨兵空轉 — **證據:** api-failure-states.spec.ts:302-343;mockSentinels.test.ts:37-64;18/18 兩邊 Browser E2E | closed |
| T-05-04 | Information Disclosure | apiClient 非信封處理 | low | mitigate | 502 HTML 轉 `HTTP_ERROR` 靜態碼,E2E 斷言頁面不含 `Bad Gateway` body;transportFetch 不帶原例外訊息 — **證據:** apiClient.ts:185-197、:284-289;api-failure-states.spec.ts:332 | closed |
| T-05-05 | Information Disclosure | 前端 ci.yml browser-e2e | medium | mitigate | `permissions: contents: read`、預設 `github.token`、無 secrets;註明後端轉 private 時的處置 — **證據:** 前端 ci.yml:40-41 `permissions: contents: read`,無 secrets | closed |
| T-05-06 | Tampering | vite.config proxy target | low | accept | 只影響 dev / preview(非正式部署);`process.env` 不進 bundle — **證據:** accept 理由成立:vite.config.ts:18-19 只在 Node 端讀 env | closed |
| T-05-07 | Elevation of Privilege | BrowserCsrfFilter(trades、login/register) | high | mitigate | G2 / G3 IT 鎖住「cookie 認證 + 無 CSRF = 403」 — **證據:** TradingApiIT.java:108-127;BrowserAuthFlowIT.java:112-139、:150-176;IT job 129584199208 | closed |
| T-05-08 | Elevation of Privilege | 登出撤銷 | high | mitigate | G4 IT 鎖住登出後舊 access cookie 打 /me = 401 — **證據:** BrowserAuthFlowIT.java:186-209;contract-journey.spec.ts:374-385 | closed |
| T-05-09 | Information Disclosure | method-security 403 | low | mitigate | G5 斷言 403 為標準失敗信封(無堆疊、帶 traceId) — **證據:** MethodSecurityDenialIT.java:48-61;SecurityConfig.java:363-364 | closed |
| T-05-10 | Elevation of Privilege | access cookie / JWT TTL | medium | mitigate | 預設皆 PT15M + 啟動守衛,外洩 cookie 最多 15 分鐘有效 — **證據:** application.yaml:75、:87 PT15M;AccessTokenTtlConsistencyGuard.java:37-41 | closed |
| T-05-11 | Information Disclosure | 守衛例外訊息 | low | mitigate | 靜態訊息只指名變數,不帶實際值(測試斷言) — **證據:** AccessTokenTtlConsistencyGuardTest.java:43-52 | closed |
| T-05-12 | Denial of Service | 啟動守衛 | low | accept | 顯式設定不相等的部署會拒絕啟動 —— 屬預期;契約與設定文件寫明兩值必須成對 — **證據:** guard 存在、契約與設定文件寫明成對;但 `.env.example:17` 仍為 PT30M 且無成對 cookie TTL → 照 README 起步會被 guard 拒絕啟動(fail-closed) | open — below high threshold (non-blocking) |
| T-05-13 | Repudiation | E2E 測試歸屬 | medium | mitigate | E2eNamingRulesTest(allowEmptyShould(false))+ 05-12 以 CI artifact 確認 7 個 E2E 在 E2E job 執行且綠 — **證據:** E2eNamingRulesTest.java:44-48、:58-64;E2E job 129586115603 | closed |
| T-05-14 | Tampering | 共用 DB / Redis 的測試資料 | low | mitigate | 每個 IT 獨立 database 與 Redis db index;flushDb 取代 flushAll — **證據:** MarketDataContainers.java:111 freshDatabase;各 IT 獨立 Redis index;無 flushAll | closed |
| T-05-15 | Tampering | 正式 Kafka topic / group 預設值 | medium | mitigate | placeholder 預設值為編譯期常數拼接;KafkaTopicPlaceholderTest 鎖住預設值;stock-start IT / E2E 與 e2e-browser 不設 property 即走預設 — **證據:** KafkaConfig.java:100-113;KafkaTopicPlaceholderTest.java:64-89 | closed |
| T-05-16 | Repudiation | IT 順序相依造成假綠 | low | mitigate | failsafe runOrder=random(CI 每次亂序) — **證據:** stock-module-market-data/pom.xml:187 runOrder=random;seed 176983128627 | closed |
| T-05-17 | Information Disclosure | executedAt 錯誤 fields | low | mitigate | fields 值為靜態字串 `must not be in the future`,單元測試以 canary 斷言不回射 — **證據:** TradingService.java:386-392;TradingServiceTest.java:398-403 | closed |
| T-05-18 | Tampering | watchlists.name | low | mitigate | V20 CHECK 與服務層同字元集,IT 以直接 insert 驗證 — **證據:** V20__watchlists_name_blank_like_check.sql;WatchlistApiIT.java:216-240 | closed |
| T-05-19 | Spoofing | 錯誤 / 失敗狀態的視覺呈現 | low | mitigate | 固定色 `--danger` + 源碼守門,紅漲模式下錯誤不再顯示為綠色(避免誤判為成功) — **證據:** styles.css:30,39;contrastTokens.test.ts:581-645 | closed |
| T-05-20 | Tampering | 未來時間 UX 檢查 | low | accept | 前端檢查可被繞過屬預期;後端 `resolveExecutedAt` / `CashLedgerService` 仍拒絕並回 fields(05-09) — **證據:** accept 理由成立:TradingService.java:386-392、CashLedgerService.java:339;TradingApiIT.java:644、CashEntryApiIT.java:331-341 | closed |
| T-05-21 | Tampering | Wave 順序 | medium | mitigate | blocking human-action checkpoint;develop CI 綠才進 05-13 — **證據:** Wave 1 PR 合併 02:29Z,develop 42946358757 / 42946379447 綠後才有 05-13 首個 commit | closed |
| T-05-22 | Tampering | 金額精度(JSON double) | medium | mitigate | `@DecimalString` 以 toPlainString 輸出字串;精度回歸 IT 原樣存讀 — **證據:** PlainDecimalStringSerializer.java:48-49;CashEntryApiIT.java:116-133、TradingApiIT.java:1057-1073;IT job 130362316276 | closed |
| T-05-23 | Information Disclosure | 壞數字字串的 400 | low | accept | 既有 `GlobalExceptionHandler` 對 `HttpMessageNotReadableException` 回靜態訊息、無 fields(不回射輸入),本 plan 不改 — **證據:** accept 理由成立:GlobalExceptionHandler.java:160-165 靜態訊息,本 phase 未改 | closed |
| T-05-24 | Tampering | 漏標的 BigDecimal 欄位以 double 輸出 | medium | mitigate | ArchUnit 規則 allowEmptyShould(false) + 行為樣本 — **證據:** ApiDecimalStringRulesTest.java:78-83(allowEmptyShould(false))、:88-164 | closed |
| T-05-25 | Tampering | 新舊快取 JSON 混讀 | low | mitigate | 鍵前綴升 v4,舊鍵 60 秒過期 — **證據:** PortfolioCache.java:59-61 v4 + TTL 60s;PortfolioCacheTest.java:87-96 | closed |
| T-05-26 | Tampering | 交易冪等(字串金額不同 scale) | medium | mitigate | `stringAmountReplayReturnsExistingTrade` 鎖住 compareTo 語意,持倉只套用一次 — **證據:** TradingApiIT.java:1015-1045;TradePayloadMatcher.java:38-42 compareTo | closed |
| T-05-27 | Tampering | 金額精度(number 混入 decimal 路徑) | medium | mitigate | `Big.strict = true`:number 建構即丟錯;wire 一律 `toFixed()`;精度往返測試 — **證據:** decimal.ts:21-24、:40-45、:57-61;decimal.test.ts:17-62 | closed |
| T-05-28 | Tampering | 顯示捨入 / 權重計算 | low | mitigate | Decimal `toFixed(dp, roundHalfUp)` 與後端 HALF_UP 一致;權重 div 後明確 round(8);測試以既有渲染文字比對;holdingWeight 交接點由 05-18 以 grep 歸零 — **證據:** moneyFormat.ts:68,73,141;Positions.vue:704-708 | closed |
| T-05-34 | Tampering | 可賣數量 / 超賣比較精度 | low | mitigate | `sellableQty` 為 Decimal,以 `gt` 比較(不經 JS number);小數股數(`"12.5"`、8 位小數)有測試;後端 409 `TRADE_INSUFFICIENT_HOLDING` 仍是最終權威 — **證據:** OrderTicket.vue:1047、:1070-1071;OrderTicket.test.ts:1789、:1805 | closed |
| T-05-29 | Tampering | 交易 payload 金額格式 | medium | mitigate | `toWire`(toFixed,無科學記號)逐欄投影;同 key 重試 payload 逐位元穩定(測試);文字輸入以 `parseDecimalInput` 驗證 — **證據:** tradingApi.ts:62-64;OrderTicket.vue:919-935;OrderTicket.test.ts:2181-2206 | closed |
| T-05-30 | Tampering | 股利 / 預扣稅 / 配股數量精度 | low | mitigate | Decimal + `toWire`;選填送 null;精度往返測試;沖銷值逐欄取自原列(後端 DB 再驗證與原列相同) — **證據:** holdingEventApi.ts:66-70、:141-146;HoldingEventDialog.vue:1446-1466;DividendService.java:325、ShareAdjustmentService.java:407 | closed |
| T-05-35 | Tampering | CSV 匯出金額欄(科學記號 / 公式注入) | low | mitigate | 金額欄一律 `toWire`(toFixed 一般記號);`csvCell` 的 `PLAIN_NUMBER` 判斷不變,非純數字仍加公式前綴;新增 `0.00000001` / `-12.50` 案例 — **證據:** Trades.vue:827-831,899-905;csvCell.ts:12-17;csvCell.test.ts:31-36 | closed |
| T-05-36 | Tampering | REST 報價解析與疊加(null 價格、number 混入) | low | mitigate | strict mapper:wire 為 number 時丟錯;null 價格保持 null 並顯示 `—`(不當 0);疊加以 Decimal 計算、changePercent 與後端 HALF_UP 同捨入 — **證據:** marketApi.ts:86-90;marketApi.test.ts:175;Watchlist.vue:935 | closed |
| T-05-31 | Tampering | WS tick 價格解析 | low | mitigate | 只接受字串、`parseDecimal` 失敗即丟棄 tick;疊加以 Decimal 計算 — **證據:** quoteStream.ts:112-131;quoteStream.test.ts:166-174 | closed |
| T-05-32 | Repudiation | 契約文件與實作漂移 | low | mitigate | mock-to-real-contract 範例改字串;decimalBoundary 源碼守門防元件回到 wire 型別 — **證據:** mock-to-real-contract.md:123,181;decimalBoundary.test.ts:91-110 | closed |
| T-05-33 | Denial of Service | 單邊合併造成 develop / 部署不相容 | medium | mitigate | blocking human-action checkpoint;`<merge_order>` 宣告「前端先、後端緊接」(過渡期 fail-closed)並寫進兩個 PR 內文;合併前再確認 head SHA / mergeable / CI;merged_at 順序驗證;無法緊接時以 revert PR 撤回;兩邊合併後重跑 develop CI 並記錄 — **證據:** FE #23 merged 02:26:35Z 先於 BE #56 02:26:51Z;develop 43141443593 / 43141473501 綠 | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-05-01 | T-05-06 | proxy target 只影響 dev / preview,`process.env` 不進 bundle | plan-phase(05-03) | 2026-11-07 |
| AR-05-02 | T-05-20 | 前端未來時間檢查可被繞過;後端 `resolveExecutedAt` / `CashLedgerService` 仍拒絕 | plan-phase(05-11) | 2026-11-07 |
| AR-05-03 | T-05-23 | 壞數字字串的 400 由既有 handler 回靜態訊息,不回射輸入 | plan-phase(05-13) | 2026-11-07 |
| AR-05-04 | T-05-SC | 除 05-16 外各 plan 無新依賴;05-13 的 jackson-databind 由 BOM 管理 | plan-phase | 2026-11-07 |

T-05-12(low,accept)**尚未列入**:accept 理由的「設定文件寫明兩值必須成對」被 `.env.example:17` 推翻,待 Yuan 選擇修正 `.env.example` 或接受為已知文件落差。

---

## Audit Findings(不在 register 內,WARNING,不阻擋)

- **Actions script-injection 寫法**:前端 `.github/workflows/ci.yml:53`(05-03 `63afeb6` 引入)把 `${{ github.head_ref || github.ref_name }}` 直接插進 `run:`;後端 `.github/workflows/ci.yml:247` 同寫法(早於本 phase)。觸發為 `pull_request`(非 `pull_request_target`)、`contents: read`、無 secrets,實際提權空間有限。加固:改以 `env: CANDIDATE: ${{ ... }}` 傳入,腳本引用 `"$CANDIDATE"`。
- **T-05-09 L1 註**:403 信封測試未明確斷言「無 stack 欄位」,證據依賴 handler 只寫靜態 `ErrorCode`。

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-12 | 36 | 35 | 1(non-blocking,low) | gsd-security-auditor(execute-phase verify:post) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [ ] Accepted risks documented in Accepted Risks Log(T-05-12 待 Yuan 決定)
- [x] `threats_open: 0` confirmed
