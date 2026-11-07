# quick-261107-1j4 Task 1(後端)執行報告

> 執行者：Task 1 executor（後端 repo `/home/user/stock-web-v2`，分支 `claude/epic-curie-lj8alo`）
> 日期：2026-11-07
> 範圍：PLAN Task 1 子項 1a–1d（B2 下限、04.2 WR-01 / WR-03、IN-01 ~ IN-04）
> 狀態：**完成**。12 個 commit，未 push；本報告、PLAN、SUMMARY、STATE、ROADMAP 都沒有 commit。

## 1. Commit 清單(`e7652d1..c7d899f`,共 12 個)

| # | Hash | 子項 | 類型 | Subject |
|---|---|---|---|---|
| 1 | `995159a` | 1a | RED | test: 現金 occurredAt 與交易 executedAt 早於 1900-01-01 應回 400 欄位錯誤 |
| 2 | `1048b0f` | 1a | GREEN | fix: 現金 occurredAt 與交易 executedAt 加 1900-01-01 下限 |
| 3 | `2cd7b1c` | 1b | RED | test: 標的下架後股利與股數調整的沖銷與冪等重送應成功 |
| 4 | `840dc51` | 1b | GREEN | fix: 股利與股數調整的沖銷與冪等重送不再檢查標的狀態 |
| 5 | `abee2e7` | 1c | RED | test: 觀察清單上限只計可見項目、重排後隱藏列排到尾端 |
| 6 | `44c954f` | 1c | GREEN | fix: 觀察清單上限只計可見項目並於重排後重編隱藏列 |
| 7 | `c530345` | 1d IN-01 | docs | docs: 更正 insertIfAbsent 對不指定目標 ON CONFLICT 的說明 |
| 8 | `caf8e69` | 1d IN-02 | RED | test: SHARE_ADJUSTMENT_NOT_REVERSIBLE 訊息應涵蓋非最晚與沖銷列兩種情境 |
| 9 | `db93cdb` | 1d IN-02 | GREEN | fix: SHARE_ADJUSTMENT_NOT_REVERSIBLE 預設訊息中性化 |
| 10 | `76ad7ce` | 1d IN-03 | refactor | refactor: 股數調整沖銷沿用已解析的調整類型,不重複解析 |
| 11 | `d93e120` | 1d IN-04 | RED | test: 觀察清單名稱只含不換行空白時應被拒 |
| 12 | `c7d899f` | 1d IN-04 | GREEN | fix: 觀察清單名稱只含不換行空白時視為空白 |

- scope 都是 `quick-261107-1j4`,subject 用繁中,每個 commit 結尾都有兩行 trailer(`git log e7652d1..HEAD` 的 trailer 行數為 24 = 12 × 2)。
- Plan ledger:`plan_head_before = e7652d18e67b140cf3f0e6e6fd2cc38b17af62ba`、`plan_head_after = c7d899f`、`git rev-list --count` = 12。
- 差異總量:24 個檔案,+723 / −86。

## 2. 測試結果

### 每個 RED 實際失敗的情形

| 子項 | RED 結果 |
|---|---|
| 1a | `CashLedgerServiceTest` 3 個 failure、`TradingServiceTest` 3 個 failure(參數化的三個早於下限的值);邊界值與「未來錯誤無 fields」兩案原本就綠 |
| 1b | Dividend 4 個 error、ShareAdjustment 4 個 error,都是 `Business Asset is not tradeable` |
| 1c | 編譯失敗(`countVisibleItems` / `renumberHiddenItemsAfter` 還不存在) |
| IN-02 | `ErrorCodeTest.shareAdjustmentCodes_httpStatusesAndMessagesAreFrozen` 1 個 failure |
| IN-04 | `WatchlistServiceTest.create_nameOfOnlyNonBreakingSpaces_rejected` 1 個 failure(expected VALIDATION_FAILED) |

### 指定的測試類別(plan verify 第 1 條,exit 0)

| 類別 | 本次 | 執行前 | 新增 |
|---|---|---|---|
| ErrorCodeTest | 35 / 0 fail | 35 | 0(只改字面) |
| WatchlistServiceTest | 33 / 0 fail | 30 | +3 |
| DividendServiceTest | 38 / 0 fail | 33 | +5 |
| ShareAdjustmentServiceTest | 44 / 0 fail | 39 | +5 |
| CashLedgerServiceTest | 40 / 0 fail | 36 | +4 |
| TradingServiceTest | 50 / 0 fail | 45 | +5 |

### 全 reactor 單元測試 `./mvnw -o -B test -Dtest='!*IT,!*E2E*'`:**BUILD SUCCESS,共 971 案,0 failure / 0 error**

common 106、infrastructure 42、user 24、asset 56、backtest 45、market-data 222、trading 458、start 18。

### 不加過濾的 `./mvnw -o -B test`

stock-start 之前的模組全綠(數字同上)。stock-start 以 BUILD FAILURE 結束:33 案中 15 個 error,全是 `*E2ETest` 類別(WsAuthFlow / WsConnectionLimit / WsTokenRevocation / WsSubscriptionLimit / WsSubscribeReceive / BackfillAdminFlow / MarketRestApi…),原因都是 `IllegalStateException: Could not find a valid Docker environment`(Testcontainers)。這是本機沒有 Docker 的環境限制,跟本 task 無關,所以 plan 的閘門以 `!*E2E*` 排除它們。

### 其他閘門

- `./mvnw -q -o -pl stock-start -am test-compile`:exit 0(含全部新增 / 修改的 IT)
- `./mvnw -q -o spotless:check`:exit 0

### grep 閘門(前值 → 本次)

| 閘門 | 前值 | 本次 | 結果 |
|---|---|---|---|
| CashLedgerService / TradingService 的 `must not be before 1900-01-01` | 0 / 0 | 1 / 1 | ≥ 1,通過 |
| ErrorCode 的 `Only the latest share adjustment of an asset can be reversed` | 1 | 0 | = 0,通過 |
| Dividend + ShareAdjustment repo 的 `（uuid 唯一、CHECK、外鍵）` | 2 | 0 | = 0,通過 |
| ShareAdjustmentService 的 `fromApiValue(request.type())` | 2 | 1 | = 1,通過 |
| `countVisibleItems` / `renumberHiddenItemsAfter` / `isSpaceChar` | 0 / 0 / 0 | 都有 | 通過 |
| trading-portfolio-contract 的 `1900-01-01` 次數;watchlist-contract 的 `2026-11-07` | 5 / 0 | 10 / 2 | ≥ 8 且有,通過 |

## 3. 待 CI 的 IT 案例(本機沒有 Docker,只確認 test-compile 通過,**沒有宣稱通過**)

| 類別#方法 | 子項 | 驗證內容 |
|---|---|---|
| `CashEntryApiIT#occurredAtBeforeFloorNamesTheField` | 1a | `-10000-01-01T00:00:00Z` 回 400,`fields.occurredAt = must not be before 1900-01-01`;error 不含 `10000`;分錄數為 0 |
| `TradingApiIT#executedAtBeforeFloorNamesTheField` | 1a | 同上,檢查 `fields.executedAt`;交易數為 0 |
| `DividendApiIT#reversalAndReplaySucceedAfterAssetIsDeactivated` | 1b | 先記一筆 AMZN 股利 → 把 AMZN 設為 `active=false` → 原 key 重送回同一 id → 沖銷 200 → 沖銷以同 key 重送回同一 id → 新建回 404 `fields.symbol = not tradeable` → 列數 2;finally 還原 `active = true` |
| `ShareAdjustmentApiIT#reversalAndReplaySucceedAfterAssetBecomesUntradeable` | 1b | 買 META 100 股 → 記拆股 4:1(昨天)→ 把 META 設為 `tradeable=false` → 沖銷 200、持倉 100 → 同 key 重送回同一 id、持倉仍 100 → 新建回 404 `not tradeable` → 列數 2;finally 還原 `tradeable = true` |
| `WatchlistApiIT#hundredItemCapCountsOnlyVisibleItems` | 1c | 種 100 檔暫存資產,其中 3 檔設為 inactive → GET 回 97 檔 → 再加入第 101 檔成功、items 為 98 筆;finally 刪除暫存資產 |
| `WatchlistApiIT#reactivatedHiddenItemAppearsAtTheEndAfterReorder` | 1c | 4 檔暫存資產,002 下架 → 重排 [004, 003, 001] → 002 重新上架 → 順序為 004, 003, 001, 002;finally 刪除 |

## 4. PostgreSQL 16 暫時 cluster 驗證(WR-03 的 SQL)

- 指令:`mktemp -d /tmp/pg-1j4-XXXX`(chown 給 postgres)→ `su postgres -c "initdb -D $D/data -A trust"` → `pg_ctl -o '-p 55439 -k $D' start` → 用 `psql -f wr03.sql` 跑最小 schema(assets / watchlists / watchlist_items,約束與 V18 相同)→ `pg_ctl stop` → `rm -rf $D`(已確認刪除)。
- 為什麼不放 scratchpad:scratchpad 上層目錄是 root 0700,postgres 使用者進不去,而 initdb 不能用 root 執行。所以改在 `/tmp` 下開一個暫存目錄,用完就刪。
- 輸出摘要(完整輸出在 scratchpad 的 `wr03-pg.out`):
  - 101 檔資產、清單 1 有 100 項、其中 3 檔(id 3 / 40 / 77)設為 inactive:**`countVisibleItems` 的 SQL 回 97**。
  - 把可見項目倒序重寫成 1..97 之後,隱藏列仍是過時的 position 3 / 40 / 77,和可見項目撞號。
  - 跑完 `renumberHiddenItemsAfter(1, 97)` 的 SQL(和 repository 同一段)後:隱藏列依原相對順序變成 3→98、40→99、77→100;**清單 1 沒有任何重複 position(0 組)**;清單 2 不受影響(1:1、50:2)。
  - 三檔重新上架後,依 `(position, id)` 排序的尾端是 S001(97)、S003(98)、S040(99)、S077(100):**確定排在尾端,而且維持原相對順序**。

## 5. 偏離計畫(Deviations)

1. **[Rule 1 — 測試斷言配合計畫指定的流程]** 計畫要求的 WR-01 流程是「解析標的 → resolveOriginal → 冪等快路徑 → 只有新建才 requireTradeable」,所以新建且不可交易的請求會先呼叫一次 `findByIdempotencyKey`。既有的三個案例(`DividendServiceTest#nonTradeableAssetIsNotFoundWithSymbolField`、`#inactiveAssetIsNotFoundWithSymbolField`、`ShareAdjustmentServiceTest#untradeableAssetIsRejected`)原本用 `verifyNoInteractions(repository)`,現在改成 `verify(repository, never()).insertIfAbsent(any())`(股數調整另外保留 `verifyNoInteractions(tradingRepository, portfolioCache)`)。404 / `fields.symbol` / 不寫入 / 不鎖持倉這些行為斷言都保留。改動放在 RED commit `2cd7b1c`,有註解說明理由。
2. **[Rule 1 — 同一份文件錯誤一併修]** IN-01 除了計畫列出的 Dividend / ShareAdjustment 兩個 repository,`JdbcCashLedgerRepository#insertIfAbsent` 也有同樣「(uuid 唯一、CHECK、外鍵)不受影響」的錯誤敘述,在 `c530345` 一起改掉(只動 Javadoc)。`ai-docs/` 內沒有同樣的敘述。
3. **[IT 資產選擇]** 計畫建議「用專屬暫存資產,或在 finally 還原 active」。`cash_dividends` / `share_adjustments` 是 append-only(V16 / V17 的 no-delete trigger),被引用的暫存資產刪不掉,而 `FoundationMigrationIT` 斷言資產總數是 19,所以改用種子資產 AMZN(股利)和 META(股數調整),在 try/finally 還原狀態。failsafe 沒有設定平行執行,也不會影響其他 IT。
4. **[路徑更正,不影響實作]** 計畫 `<interfaces>` 寫的是 `POST /api/v1/cash/entries`,實際端點是 `POST /api/v1/portfolio/cash-entries`(IT 和契約都用實際路徑)。錯誤形狀和計畫一致。
5. IN-01 / IN-03 照計畫做成單一 docs / refactor commit;1a / 1b / 1c / IN-02 / IN-04 都是 RED 和 GREEN 各一個 commit。

## 6. 給 Yuan 的觀察

- **executedAt「未來」錯誤沒有 fields**(照計畫維持現狀):`POST /trades` 的 `executedAt` 晚於現在 5 分鐘以上時,仍回沒有 `error.fields` 的 400 `VALIDATION_FAILED`(`executedAt must not be in the future`),所以前端只能在底部顯示一般錯誤。新加的 1900 下限則有 `fields.executedAt`。契約已把兩種形狀分開列。之後要統一的話,把那條 `BusinessException` 改成 `FieldValidationException` 即可(要改契約,也要通知前端)。
- **IN-04 沒有擴充 DB CHECK**:`ck_watchlists_name_not_blank` 仍然是 `btrim(name) <> ''`,擋不住只含 U+00A0 的名稱。擴充需要 V20 migration(不在範圍內)。目前服務層是唯一寫入者,已經擋下;Javadoc 和契約都註明了。
- **WR-03 的副作用**:可見項目數現在可能暫時超過 100(資產重新上架時),這時加入會回 400,要移除到低於 100 才能再加入;契約已寫明。
- **WR-01**:活動列表的 `reversible` 沒有改,仍然只看帳本(計畫明訂)。標的下架後,前端仍可依 `reversible` 顯示沖銷鈕,後端現在也會接受這個沖銷。

## 7. Task 3 要用的「04.2 finding → commit」對照

| Finding | 處置 | Commit |
|---|---|---|
| WR-01 沖銷 / 冪等重送不檢查資產狀態 | 已修正(新建才 requireTradeable) | RED `2cd7b1c`、GREEN `840dc51` |
| WR-03 上限只計可見項目、隱藏列重編 | 已修正 | RED `abee2e7`、GREEN `44c954f` |
| IN-01 insertIfAbsent Javadoc(uuid 唯一也會被吸收) | 已更正(另含 CashLedger) | `c530345` |
| IN-02 SHARE_ADJUSTMENT_NOT_REVERSIBLE 訊息中性化 | 已修正 | RED `caf8e69`、GREEN `db93cdb` |
| IN-03 type 不重複解析 | 已重構 | `76ad7ce` |
| IN-04 全 NBSP 名稱被拒 | 已修正(服務層;DB CHECK 未擴充) | RED `d93e120`、GREEN `c7d899f` |
| B2(Yuan 3B)occurredAt / executedAt 1900 下限 | 已實作 | RED `995159a`、GREEN `1048b0f` |

## 8. 動到的檔案

- 主程式:`CashLedgerService`、`TradingService`、`DividendService`、`ShareAdjustmentService`、`JdbcDividendRepository`、`JdbcShareAdjustmentRepository`、`JdbcCashLedgerRepository`(Javadoc)、`ErrorCode`、`WatchlistRepository`、`JdbcWatchlistRepository`、`WatchlistService`
- 單元測試:`CashLedgerServiceTest`、`TradingServiceTest`、`DividendServiceTest`、`ShareAdjustmentServiceTest`、`ErrorCodeTest`、`WatchlistServiceTest`
- IT:`CashEntryApiIT`、`TradingApiIT`、`DividendApiIT`、`ShareAdjustmentApiIT`、`WatchlistApiIT`
- 契約:`ai-docs/trading-portfolio-contract.md`(1a + WR-01,〈變更紀錄〉兩條 2026-11-07)、`ai-docs/watchlist-contract.md`(WR-03 + IN-04,〈變更紀錄〉兩條 2026-11-07)

## Addendum E1(HoldingDto market)

> 執行者:E1 executor(後端 repo,分支 `claude/epic-curie-lj8alo`),2026-11-07。範圍:PLAN〈Orchestrator addendum〉E1。
> 狀態:**完成**。3 個 commit(`b1eeb29..98d4851`),未 push;本段報告未 commit。

### Commit

| # | Hash | 類型 | Subject |
|---|---|---|---|
| 1 | `aae1dc2` | RED | test: 持倉應帶資產屬性的交易市場 market 且持倉快取鍵升 v3 |
| 2 | `50b0c0e` | GREEN | feat: HoldingDto 新增 market 並將持倉快取鍵升 portfolio:valuation:v3 |
| 3 | `98d4851` | docs | docs: 契約補 holdings market 欄位並同步持倉快取鍵 v3 |

### 給前端 E2 的欄位契約

- JSON 欄位名:**`market`**,型別 `string | null`。
- 位置:`HoldingDto` 中緊接 **`sector` 之後**、`baseCurrency` 之前(`... currency, assetType, sector, market, baseCurrency, marketValueInBase, ...`)。前端以名稱讀取即可,位置只供對照。
- 值:資產的 `assets.market` 原值,與 `GET /assets` 的 `market` 同值域;種子資料出現的值為 `US`、`TW`、`JP`、`DE`(債券)、`CRYPTO`、`FX`。
- **null 的條件**:(1) 查無資產屬性(理論上不會發生,此時 `currency` / `assetType` / `sector` 也同為 null);(2) 資產的 `assets.market` 欄位為 NULL(V1 schema 允許,種子資料都有值)。前端遇 null 退回 fallback(紐約日期)。
- 快取命中與未命中的回應都帶 `market`(單元測試 `cachedHoldingKeepsMarketThroughBaseValuation` 鎖住)。
- summary(`PortfolioSummaryDto`)形狀不變。

### 快取評估:持倉鍵升版 `portfolio:valuation:v2` → `v3`

- `market` 不像 `unrealizedPnlInBase` 是回應時才補,而是在 `calculateAndCacheHolding` 隨原幣估值**寫入持倉快取**。
- 不升版的話,部署後最長 60 秒內會讀到舊 v2 JSON。Jackson 讀缺欄位的 JSON 不會報錯,只會得到 `market = null`(`PortfolioCacheTest.readHoldingsUsesSingleMultiGet` 本來就用缺欄位的 JSON 證明這一點),前端會靜默退回紐約日期。
- 所以升 `v3`,與 04.2 summary 升 `v3` 同一做法;舊 v2 鍵 60 秒後自然過期。summary 鍵維持 `portfolio:summary:v3`(summary JSON 不含 HoldingDto)。
- 測試:`legacyV2ValuationKeyIsNeverRead`(舊 v2 鍵有值也不讀)、`holdingMarketSurvivesCacheRoundTrip`(寫入 v3 鍵的 JSON 含 `"market":"TW"`,讀回 market 不遺失),既有斷言的鍵全部從 v2 改為 v3,失效路徑另斷言不刪 v2 鍵。

### 測試結果

- RED:`./mvnw -o -B -q -pl stock-module-trading -am test -Dtest='PortfolioQueryServiceTest,PortfolioCacheTest'` → testCompile 失敗(`HoldingDto` 沒有 `market()`、建構子引數數目不符),符合預期。
- GREEN 指定類別:`PortfolioQueryServiceTest` 65 / 0 fail(原 62,+3)、`PortfolioCacheTest` 13 / 0 fail(原 11,+2)。
  - 新增:`holdingsCarryMarketFromAssetProfile`(AAPL→US、2330.TW→TW,寫入快取的 DTO 也帶 TW)、`holdingMarketIsNullWhenProfileMissing`(查無屬性 → market / currency / assetType / sector 都是 null,不寫快取)、`cachedHoldingKeepsMarketThroughBaseValuation`、`legacyV2ValuationKeyIsNeverRead`、`holdingMarketSurvivesCacheRoundTrip`。
- 全 reactor 單元測試 `./mvnw -o -B test -Dtest='!*IT,!*E2E*' -Dsurefire.failIfNoSpecifiedTests=false`:**BUILD SUCCESS,976 案,0 failure / 0 error**(Task 1 結束時 971,+5):common 106、infrastructure 42、user 24、asset 56、backtest 45、market-data 222、trading 463、start 18。
- `./mvnw -q -o -pl stock-start -am test-compile`:exit 0。
- `./mvnw -q -o spotless:check`:exit 0。

### 待 CI(本機沒有 Docker,只確認 test-compile,**沒有宣稱通過**)

| 類別#方法 | 驗證內容 |
|---|---|
| `TradingApiIT`(買 AAPL 後 `GET /portfolio/holdings` 那段) | 新增 `$.data[0].market == "US"`(V2 seed AAPL 的 market) |

### 文件

- `ai-docs/trading-portfolio-contract.md`:HoldingDto 欄位表在 `sector` 後新增 `market` 列;〈快取與一致性〉持倉鍵改為 `portfolio:valuation:v3` 並說明升版原因;〈變更紀錄〉新增 2026-11-07 條目。該契約的 holdings 節沒有範例 JSON,所以不需要改範例。
- 同步持倉鍵 v2 → v3:`ai-docs/redis-convention.md`、`ai-docs/code-standards.md`、`ai-docs/judgment.md`(Rule 2:文件會指向一把已經不用的鍵)。
- `HoldingDto` Javadoc 新增 `@param market`,版本 3.1 → 3.2;`PortfolioCache` 的版本沿革補上持倉鍵 v3,版本 4.0 → 4.1。

### 偏離計畫

1. **[Rule 2 — 文件一致性]** 計畫只要求更新 trading-portfolio-contract;持倉鍵升版後,redis-convention / code-standards / judgment 三份文件仍寫 `v2`,所以一起改成 v3(放在 docs commit `98d4851`)。
2. **[發現,不影響實作]** `assets.market` 在 V1 schema 是可為 NULL 的 `VARCHAR(50)`,`AssetFacadeImpl.toProfile` 原樣傳遞。所以除了查無資產屬性,`market` 在資產未設定市場時也會是 null;契約和 Javadoc 都寫明了。這種情況沒有另寫單元測試(原值傳遞,`TradingDay.of` 本來就要處理 null market)。
3. RED commit 同時把 4 個既有 `HoldingDto` fixture 補上 market 引數(只改測試檔),所以 GREEN commit 只動主程式。

### 給前端的對照(後端交易日規則,`stock-common` `TradingDay.of(assetType, market)`)

- `assetType = CRYPTO` → UTC 00:00(不看 market);`FX` → 紐約 17:00(不看 market)。
- 其餘依 market(後端會 `trim` + 轉大寫再比對;`market` 欄位本身回傳 DB 原值,不正規化):`US` / `NASDAQ` / `NYSE` / `AMEX` → 紐約午夜;`TW` / `TWSE` / `TPEX` → 台北午夜;`JP` / `TSE` → 東京午夜;`DE` / `XETRA` → 柏林午夜;其他值或 `market` 為 null → **UTC**。
- 觀察:後端在 market 為 null 時退回 UTC,計畫 E2 寫的前端 fallback 是紐約日期,兩者不同。實務上種子資料不會出現 null,所以不影響;是否要對齊請 Yuan 決定。
