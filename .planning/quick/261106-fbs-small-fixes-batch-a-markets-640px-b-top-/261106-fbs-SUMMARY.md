---
quick_id: 261106-fbs
slug: small-fixes-batch-a-markets-640px-b-top-
date: 2026-11-06
status: complete
repos:
  backend: /home/user/stock-web-v2 (claude/epic-curie-lj8alo)
  frontend: /home/user/stock-web-v2-front-end (claude/epic-curie-lj8alo)
todos_closed:
  - 2026-10-25-cash-occurred-at-empty-copy
  - 2026-10-26-contrast-text-tokens
  - 2026-10-25-top-movers-base-currency
  - 2026-10-26-phase-04.1-review-followups
  - 2026-10-31-full-docs-sweep
todos_opened:
  - 2026-11-06-cash-amount-string-serialization (04.1 WR-04 → Phase 5)
---

# Quick 261106-fbs — 小型修補合併批次

Yuan 於 2026-11-06 核准把四批小型修補合併為單一 quick task。Plan:`261106-fbs-PLAN.md`(含 orchestrator 的 2c-addendum)。三個 task 由三個 executor 執行:Task 1(後端)與 Task 2(前端)平行,Task 3(文件)在兩者完成後執行。詳細內容見同目錄的 `task1-report.md`、`task2-report.md`、`task3-report.md`。

## Yuan 的裁決(本 task 依據)

- 合併為單一 quick task;04.1 WR-01(匯率時間)延後至 fx-pnl-and-rate-history,04.1 WR-04(金額字串化)延後至 Phase 5。
- 對比度配色(樣式頁 https://claude.ai/artifact/VqvnT6zvNXF34UeAAWbPpx 回覆「ok」= 採建議組合):淺色漲文字 `--up-text #15803d`;橘底按鈕保留 `#ff6600`、文字改深色 `#15171c`(`--on-accent`);深色主題 `--dn #ef4444`;提示字 `.quote-lab` 改 `--fg-dim`。
- 追加(AskUserQuestion):「記錄交易」按鈕高 44px → 36px(min-width 136px 保留);買進鈕綠底改 `#15803d` 白字。Orchestrator 因深色 `--dn` 改色,讓買賣鈕使用獨立底色 token `--btn-up-bg #15803d` / `--btn-dn-bg #dc2626`(白字 5.02 / 4.83,兩主題同值,紅漲綠跌時對調)。

## 交付

**Task 1 後端(13 commits,da4b541..7221c7f)**
- `HoldingDto.unrealizedPnlInBase`(缺匯率 null);契約檔同步。
- 04.1 WR-02:`AssetFacadeImpl` 壞 currency / asset_type 列略過並 WARN(只記 id);V19 `ck_assets_currency`(本機 PG16 以真實 V1–V19 驗證,19 筆 seed 合法、HKD 被拒)。
- 04.1 WR-03:`listTrades` 與 summary 改 REPEATABLE_READ readOnly(反射測試)。
- 04.1 IN-01 / IN-05:移除死碼;缺資產屬性的今日損益候選計數一致(順帶修掉一個重複計數)。
- 04.2 WR-02:觀察清單加入代號大小寫不敏感。04.2 WR-04:paidAt / effectiveDate 下限 1900-01-01,極端日期回 400。

**Task 2 前端(10 commits,b4ff61e 起)**
- Top movers 以 `unrealizedPnlInBase` 排序、金額為數字 + 基準幣 ISO,缺值不進榜並提示。
- 入出金清空發生時間的專屬文案 `cashErrOccurredAtRequired`(zh/en)。
- 對比度 token 全套(含 2c-addendum 的買賣鈕底色、OrderTicket 36px 按鈕);22 條 accent 底白字規則改 `--on-accent`;TweaksPanel 七種可選 accent 皆達 AA(最低 4.53:1)。
- Markets < 640px 不再撐寬 `.main`;layout L12 收緊為 320 / 375 / 768 / 1280 開啟選擇器前後皆不捲,新增 L17。
- 04.1 IN-02 / IN-03 / IN-04:現金餘額 / 錯誤對應抽共用、Overview 交易載入 stale-response 守門、`sectorAllocation ?? []`。

**Task 3 文件(後端 6 commits、前端 2 commits,只動 .md)**
- 04.2 G2 三處;04.1 / 04.2 審查處置更新(04.2 open 14 → 12);04.1 UI-SPEC 補新鍵。
- ai-docs 逐份對照程式校正(security / architecture / flyway / redis / code-standards / testing-standards / browser-auth / git-convention);`.planning/codebase/` 七份 targeted refresh。
- 前端 AGENTS.md、mock-to-real-contract(信封一節依後端、Ops / AI Access 標示未實作)、歷史文件加標示。
- 5 條 todo 關閉;新增 Phase 5 todo `cash-amount-string-serialization`。

## 驗證

| 項目 | 結果 |
|---|---|
| 後端全 reactor 單元測試 | 949 tests,0 failures(asset 53、trading 439 …) |
| 後端 spotless / test-compile | 通過 |
| 前端 `npm test`(mock / api) | 各 59 檔、1138 / 1138 |
| 前端 build / lint | 通過;lint 0 errors,warning 數未增加 |
| layout Playwright | 97 → 101 passed |
| Task 3 `<automated>` verify | 8 / 8 PASS;只改 .md |
| 待 CI(本機無 Docker,只 test-compile,未宣稱通過) | TradingApiIT(unrealizedPnlInBase)、AssetFacadeImplIT(壞列略過、HKD 被 CHECK 拒)、WatchlistApiIT#addItemAcceptsLowerCaseSymbol、DividendApiIT(極端 paidAt 400)、ShareAdjustmentApiIT#effectiveDateBeforeFloorIsRejectedWithFieldError |

## 偏離

- 前端改了 `vite.config.ts`(`test.css.include` 只對 `src/styles.css`),讓對比度 token 測試能讀到 CSS。
- L17 抓到 OrderTicket 在 320px 時 footer 被「← 返回修改」撐成兩行(既有問題):加 ≤400px media query,交易單在窄螢幕約寬 12px。
- AuthPanel `.auth-submit.danger`、SettingsBrokers 警示圖示、Chart 買賣鈕一併改用按鈕底色 token + 白字(避免深色字疊紅底)。
- 前端 2e GREEN commit type 寫成 `refactor`(含行為修正,應為 `fix`);依規定不 amend。
- `.planning/codebase/` 為 GSD 管理檔,本次因無法派 mapper agents 改為 targeted 手改。

## 需 Yuan 決定(本 task 未處理)

UI / 程式:
1. CashEntryDialog、HoldingEventDialog 的主要按鈕仍 44px 高,是否統一為 36px。
2. 以 `--accent` 當文字色的地方(Markets `.tab-count`、`th.s`)白底 2.94:1。
3. 成功打勾圓圈用 `--up`,紅漲綠跌時變紅;是否另立 success token。
4. `--fg-mute` 其他提示字對比 2.56(淺)/ 2.85(深)。
5. HoldingEventDialog 清空入帳時間仍顯示「不可晚於現在」;後端早於 1900 也回同欄位錯誤,文案需調整。
6. CashLedger `occurredAt`、Trading `executedAt` 也只有上限、沒有下限。
7. OrderTicket 仍保留 MKT / LMT 與 TIF 標籤(payload 已不含),是否違反鐵律 5 的精神。

文件 / 流程(task3-report.md「需 Yuan 裁決」有證據與選項):
8. 前端 sibling 路徑以 `../../vue/stock-v2`(本機)或 `../stock-web-v2-front-end`(cloud)為準。
9. 後端 AGENTS.md 仍過時,是否跑 `/gsd-docs-update`;`.planning/codebase/` 是否跑 `/gsd-map-codebase` 全量重產。
10. 「新增 ai-docs 必須 `git add -f`」規則是否保留;commit scope 規則(module vs phase / quick id)是否正式改寫;後端規則檔前端驗證是否加 `npm run lint`。
11. 前端 LEARNINGS 2026-07-07 第 2 條是否標 resolved;遺失的 `docs/plans/2026-03-21-architecture-design.md` 是否進版控。
12. mock-to-real-contract 的 Ops / AI Access 提案去留;security.md / architecture.md「設計目標、未實作」段落要排 todo 或移出規範檔;D-01 日期以裁決日或合併日為準。
