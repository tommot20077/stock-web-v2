# quick-261106-fbs — Task 1(後端)執行報告

> 由 Task 1 executor 撰寫，供 orchestrator 組裝 SUMMARY；本檔不 commit。
> repo：`/home/user/stock-web-v2`，分支 `claude/epic-curie-lj8alo`。

## 範圍

Task 1 = B 後端 `unrealizedPnlInBase` + C 後端穩健性（04.1 WR-02 / WR-03 / IN-01 / IN-05、04.2 WR-02 / WR-04）。
沒有碰 Task 2（前端）與 Task 3（文件掃描 / todo 關閉），也沒有動 STATE.md / ROADMAP.md / todos。

## Commits（13 個，依時間先後）

`plan_head_before` = `da4b541d9d5a7cd18d32d8730a2a90eb95c6d443`，`plan_head_after` = `7221c7f09ab669d25b773d0e0ce118eba56d361a`，`git rev-list --count` = **13**。

| 子項 | 階段 | SHA | Subject |
|---|---|---|---|
| 1a unrealizedPnlInBase | RED | `5a56a3e` | test(quick-261106-fbs): 持倉基準幣未實現損益 unrealizedPnlInBase 的失敗測試(RED) |
| 1a | GREEN | `2cb62a1` | feat(quick-261106-fbs): 持倉回應新增基準幣未實現損益 unrealizedPnlInBase(GREEN) |
| 1b 04.1 WR-02 + V19 | RED | `67c2fac` | test(quick-261106-fbs): assets 壞列(asset_type / currency 不在 enum)略過的失敗測試(RED) |
| 1b | GREEN | `4b16897` | fix(quick-261106-fbs): assets 壞列略過並 WARN,V19 新增 ck_assets_currency(GREEN) |
| 1c 04.1 WR-03 | RED | `4ad719b` | test(quick-261106-fbs): listTrades 與 summary 須為可重複讀唯讀交易的失敗測試(RED) |
| 1c | GREEN | `2083974` | fix(quick-261106-fbs): listTrades 與 summary 改為可重複讀唯讀交易(GREEN) |
| 1d 04.1 IN-05 | RED | `35c2181` | test(quick-261106-fbs): 缺資產屬性的今日損益候選計數一致性的失敗測試(RED) |
| 1d IN-05 | GREEN | `c7a4d88` | fix(quick-261106-fbs): 缺資產屬性的平倉候選計入 dayPnlExcludedCount,持倉不重複計(GREEN) |
| 1d 04.1 IN-01 | REFACTOR | `6900780` | refactor(quick-261106-fbs): 移除未被呼叫的 PortfolioQueryService#sum 與 PortfolioCache#readHolding |
| 1e 04.2 WR-02 | RED | `9bf3f83` | test(quick-261106-fbs): 觀察清單以小寫 symbol 加入的失敗測試(RED) |
| 1e WR-02 | GREEN | `dc7267d` | fix(quick-261106-fbs): 觀察清單加入項目的 symbol 改為大小寫不敏感(GREEN) |
| 1e 04.2 WR-04 | RED | `c231197` | test(quick-261106-fbs): paidAt / effectiveDate 早於 1900-01-01 須回 400 的失敗測試(RED) |
| 1e WR-04 | GREEN | `7221c7f` | fix(quick-261106-fbs): paidAt / effectiveDate 新增 1900-01-01 下限,極端日期回 400(GREEN) |

每個 commit 皆以 Co-Authored-By / Claude-Session 兩行結尾；沒有 amend / rebase / push / stash。

## TDD RED 證據

每個 RED 都以 surefire JUnit XML 經 `gsd_run check tdd-red-evidence` 驗證為 **`RED_EVIDENCE_OK`（target_test_failed）**，
紀錄存於 `.planning/quick/261106-fbs-.../red-evidence/*.json`（**未 commit**，與本報告一樣交給 orchestrator 決定去留）。

| RED | 目標測試 | 失敗原因（語意評估） |
|---|---|---|
| 1a | `PortfolioQueryServiceTest#holdingsCarryUnrealizedPnlInBase`（另 `cachedHoldingIsReconvertedWithCurrentBase`） | `Expecting actual not to be null`：新欄位恆為 null，斷言 3241.80000000 / 6.16940000 前即失敗。合法 RED |
| 1b | `AssetFacadeImplTest#profileRowWithUnsupportedCurrencyIsSkipped`（另兩案） | `assertDoesNotThrow` 的 AssertionFailedError：`IllegalArgumentException: No enum constant CurrencyCode.HKD` / `AssetType.COMMODITY`。合法 RED（斷言的就是「不得拋例外」） |
| 1c | `TradingServiceTest#listTradesRunsInARepeatableReadReadOnlyTransaction`、`PortfolioQueryServiceTest#summaryRunsInARepeatableReadReadOnlyTransaction` | isolation 為 DEFAULT ≠ REPEATABLE_READ。合法 RED |
| 1d IN-05 | `PortfolioQueryServiceTest#closedCandidateWithoutProfileIsCountedAsExcluded`（另 `heldPositionWithoutProfileIsOnlyFxExcluded`） | `expected: 1 but was: 0`；持倉案 `expected 0 but was 1`（證實原本會重複計）。合法 RED |
| 1e WR-02 | `WatchlistServiceTest#addItem_lowerCaseSymbol_isNormalizedToUpperCase` | Mockito `Argument(s) are different! Wanted: findActiveAssetIdBySymbol("AAPL")`（實際以 "aapl" 查詢）。合法 RED |
| 1e WR-04 | `DividendServiceTest#paidAtBeforeFloorIsRejected`（另 -10000 年案）、`ShareAdjustmentServiceTest#effectiveDateBeforeFloorIsRejectedBeforeAnyFacadeCall` | 股利：錯誤碼不是 VALIDATION_FAILED（請求通過驗證後落到其他路徑）；股數調整：assertj「expected BusinessException but was NullPointerException」（請求通過驗證、深入 create 流程）。合法 RED |

RED commit 內含的最小產品碼（為了讓測試能編譯並在**斷言**上失敗，而非編譯錯誤）：
- 1a：`HoldingDto` 先加 `unrealizedPnlInBase` record component（恆傳 null）。
- 1b：`AssetFacadeImpl` 先把「原始列 → enum」抽成 package-private `toProfile` / `toSummary` + `RawProfile` / `RawSummary`，行為不變（仍拋例外）。

## 測試結果

**指定類別（plan verify #1）** — exit 0：

| 類別 | tests | failures / errors |
|---|---|---|
| AssetFacadeImplTest（新） | 5 | 0 / 0 |
| WatchlistServiceTest | 30（+1） | 0 / 0 |
| TradingServiceTest | 45（+1） | 0 / 0 |
| PortfolioQueryServiceTest | 62（+5） | 0 / 0 |
| PortfolioCacheTest | 11 | 0 / 0 |
| DividendServiceTest | 33（+3） | 0 / 0 |
| ShareAdjustmentServiceTest | 39（+2） | 0 / 0 |
| CashLedgerServiceTest | 36 | 0 / 0 |

**全 reactor 單元測試** `./mvnw -o -B test -Dtest='!*IT,!*E2E*' -Dsurefire.failIfNoSpecifiedTests=false` — BUILD SUCCESS：

| 模組 | tests | failures / errors / skipped |
|---|---|---|
| stock-common | 106 | 0 / 0 / 0 |
| stock-db-migration | —（無測試） | — |
| stock-infrastructure | 42 | 0 / 0 / 0 |
| stock-module-user | 24 | 0 / 0 / 0 |
| stock-module-asset | 53 | 0 / 0 / 0 |
| stock-module-backtest | 45 | 0 / 0 / 0 |
| stock-module-market-data | 222 | 0 / 0 / 0 |
| stock-module-trading | 439 | 0 / 0 / 0 |
| stock-start | 18 | 0 / 0 / 0 |
| **合計** | **949** | 0 |

- `./mvnw -q -o -pl stock-start -am test-compile`：通過（所有新增 / 修改的 IT 皆可編譯）。
- `./mvnw -q -o spotless:check`：通過。

**grep 閘門**（plan verify #5–#10）全部達標：
| 閘門 | 前值 | 後值 |
|---|---|---|
| HoldingDto `unrealizedPnlInBase` 次數 ≥ 2 | 0 | 6 |
| V19 存在且含 `ck_assets_currency` | 0 | 1 |
| TradingService / PortfolioQueryService `isolation = Isolation.REPEATABLE_READ` | 0 / 0 | 1 / 1 |
| `private BigDecimal sum(` / `readHolding(` | 1 / 1 | 0 / 0 |
| WatchlistService `toUpperCase(Locale.ROOT)` | 0 | 1 |
| 契約檔含 `unrealizedPnlInBase` 與 `1900-01-01` | 0 / 0 | 有 / 有 |

## V19 驗證（本機 PostgreSQL 16）

在 `/var/tmp/qt261106-pg` 建臨時 PG16 叢集（未動 `/tmp/claude-0` 權限，維持 700），依序以 psql 套用**真實** V1~V19：
V4 / V5 / V12（TimescaleDB 專用，`market_prices` / `kline_1m`，與 assets 無關）失敗，其餘全部成功，**V19 成功**。
`assets` 幣別分布 USD 13 / TWD 3 / JPY 2 / EUR 1（共 19 列，皆合法）；`insert ... 'HKD'` 被
`violates check constraint "ck_assets_currency"` 拒絕；`asset_type='COMMODITY'` 可寫入（只有 currency 有 CHECK，壞 asset_type 由應用層略過）。
`grep -i 'into assets'` 全 repo 只有 V2 與 `WatchlistApiIT#createTempAssets`（'USD'）。叢集已停止並刪除。

## 待 CI 驗證的 IT（本機無 Docker，只 test-compile；SUMMARY 不得宣稱通過）

- `TradingApiIT`：既有兩個 holdings 斷言旁新增 `unrealizedPnlInBase`（USD 基準 995.00000000；TWD 基準 AAPL 32255.91000000、2330.TW 200.00000000）。
- `AssetFacadeImplIT#rowWithUnsupportedAssetTypeIsSkipped`：暫存 `tradeable=false` 壞 asset_type 列，findProfiles / findBySymbol 略過、findAllTradeable 仍 11 筆，finally 刪除。
- `AssetFacadeImplIT#unsupportedCurrencyIsRejectedByCheckConstraint`：HKD → `DataIntegrityViolationException`，訊息含 `ck_assets_currency`。
- `WatchlistApiIT#addItemAcceptsLowerCaseSymbol`：POST `{"symbol":"aapl"}` → 200，清單含 AAPL。
- `DividendApiIT#fieldErrorsNameTheOffendingField`：新增 `"paidAt":"-10000-01-01T00:00:00Z"` → 400 `fields.paidAt`。
- `ShareAdjustmentApiIT#effectiveDateBeforeFloorIsRejectedWithFieldError`：`effectiveDate 1800-01-01` → 400 `fields.effectiveDate`，無寫入、持倉不變。

## 偏離計畫（Deviations）

1. **[Rule 1 - Bug] IN-05 順帶修正持倉的重複計數**：RED 證實「持有中且查無資產屬性、又價格缺失」的持倉原本同時計入 `fxExcludedCount` 與 `dayPnlExcludedCount`。做法是 `DayAccumulator#add` 新增 `fxExcludedYet` 參數，並把 `profile == null` 判斷移到價格缺失之前——這正是 plan T1.8 第二句要求的行為，列出供知悉。
2. **T1.1 測試數值**：plan 寫「unrealizedPnl 100 → 3241.80000000」，新測試 `holdingsCarryUnrealizedPnlInBase` 照用此值（10 股 × (110 − 100)）；另外在既有快取重算測試追加 6.16940000（200 TWD → USD）。無偏離，只是說明。
3. **1d IN-01 用 `refactor` commit 而非 RED/GREEN**：純刪除未被呼叫的程式碼，沒有可觀察行為可寫失敗測試；以既有測試全綠為依據。plan done 條件寫「每個子項都有 RED 與 GREEN」——1d 的 RED/GREEN 由 IN-05 提供，IN-01 為附加的 refactor commit。
4. **AssetFacadeImpl 的 `findBySymbol` 遇壞列回 empty**：依 plan 實作；`findAllTradeable` 同步套用（plan 已要求）。
5. 1c：查證 `summary` 只由 `TradingController` 呼叫、不在外層讀寫交易內，故照 plan 改 REPEATABLE_READ（未觸發「改為不動」的條件）。

## 給 Yuan 的後續觀察（未在本 task 處理）

- 前端 `HoldingEventDialog` 對 `fields.paidAt` 顯示的是「不可晚於現在」文案；遇到新的 1900-01-01 下限錯誤時文案不貼切（`vue-app/src/components/HoldingEventDialog.vue`，由 `fieldErrorText('paidAt')` 分派）。
- 同類缺口（只有上限、沒有下限）：`CashLedgerService` 的 `occurredAt`（約 330 行，只檢查未來）與 `TradingService` 的 `executedAt`（約 379 行，只檢查未來）。依 plan 指示**未擴大處理**，列為後續候選。
- 股數調整的極大年份（例如 `+999999999-12-31`）在 `TradingDay.startOf` 換算時是否會拋非 BusinessException 例外，未驗證；可與上一項一起評估。

## Actuals

- tokens（chars/4，實際 diff）：約 22,000（`git diff da4b541..HEAD` 89,301 chars）
- tasks：1（Task 1 的 5 個子項 1a–1e 全部完成）
- commits：13（measured）
- 檔案：23（新增 2：`V19__assets_currency_check.sql`、`AssetFacadeImplTest.java`；修改 21）

## Self-Check

- 13 個 commit 皆為 HEAD 祖先（`git rev-list da4b541..HEAD` = 13）。
- `V19__assets_currency_check.sql`、`AssetFacadeImplTest.java` 存在。
- 工作樹除 `red-evidence/`（未 commit 的 RED 證據）與本報告外乾淨。
