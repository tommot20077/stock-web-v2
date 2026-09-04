# 04-13 Task 2 的 headless 功能預檢(2026-09-04)

> **這份不是 Task 2 的通過憑證。** Task 2 是 `checkpoint:human-verify gate="blocking"`,要看的是版面比例、動畫觀感、文案在實際版位下的可讀性——這些 headless 看不出來,只有 Yuan 親眼確認才算數。
>
> 這份做的是另一件事:把 14 步裡**可自動判定的功能部分**先跑一遍,讓 Yuan 不必把時間花在「按下去沒反應是不是壞了」上,並先揪出會中斷走查的問題。
>
> 環境:後端 `develop` @ 334cb34 起於 `e2e-browser` profile(compose infra,port 8080),前端 `develop` API mode dev server(port 5173),headless Chromium。

## 走查前先修掉的阻斷問題

**dev server 沒有 `/api` 代理,步驟乙 的指令連不到後端。**

API mode 沒有任何 base-url 設定(`apiClient.ts` 一律送同源相對路徑),連線完全靠 vite 代理;而代理原本只設在 `preview`,`server` 沒有。`VITE_DATA_MODE=api npm run dev` 於是把 `/api/v1/*` 交給 SPA fallback,拿回 index.html 被當成 404,畫面顯示「暫時無法連線到後端」,看起來像後端掛了。

已修:前端 PR #11(RED → GREEN,`src/viteConfig.test.ts` 鎖住 dev 與 preview 都要有 `/api` 與 `/ws` 代理)。修正後 `04-13-PLAN.md` 步驟乙 的指令逐字可用。

## 逐步結果

| 步驟 | 檢查項 | 結果 |
|------|--------|------|
| 1 | step dots 三顆、焦點落在 symbol 輸入框 | ✅ `.step-dots` 三個子元素;`document.activeElement` 為 `ticket-symbol-input` |
| 5 | 打 `AAP` 出現真實後端標的、debounce 生效 | ✅ 回 `AAPL`(後端 seed);三個字元只發 **1 次** `/api/v1/assets` |
| 6 | 鍵盤(↓ / Enter)選得到標的 | ✅ symbol 欄位變成 `AAPL` |
| 7 | 報價卡有數字;走勢圖無資料時仍可送出 | ✅ 218.40 / +1.42 / 215.80–219.10;`ticket-quote-chart-empty` 出現,而下一步按鈕 **未** disabled |
| 8 | API mode 看不到訂單類型、TIF、交易後現金 | ✅ 三者皆不在 DOM 文字中 |
| 9 | fee 預設 0、成交時間預設現在 | ✅ `ticket-fee` = `0`;`ticket-executed-at` = 當下時間 |
| 10 | 成功畫面用後端值,交易編號完整 36 字元 | ✅ `a7535136-583a-47cd-a9cd-109f759ebe9c`(長度 36)、`$218.40` / `10`;文案無「路由 / 撮合 / Routing / Filled」 |
| 11 | Positions 與 Trades 重讀且有高亮與「新」 | ⚠️ 見下方 F-A |
| 12 | SELL 顯示可賣數量;超賣有錯誤且送出鈕不可按 | ✅ 「可賣數量:15」;填 20 → `role="alert"`「持倉不足,無法賣出這個數量,交易未記錄。請調整數量後再送出。」+ 下一步按鈕 disabled;改回 5 → 錯誤消失、按鈕恢復 |
| 13 | 離線送出的文案與 error code | ✅ 逐字符合:「無法連線到伺服器,這筆交易可能尚未記錄。直接再送出一次不會建立重複交易。」+ `NETWORK_ERROR`;⚠️ 見 F-B |
| 14 | 320px 下錯誤區塊完整換行、無水平滾動 | ⚠️ 錯誤區塊本身正確換行(244px 寬、未溢出),但整頁有水平滾動,見 F-C |
| 丙 | 英文下無 `Place order` / `Filled` / `Routing` / `Avg fill`,按鈕不溢出 | ✅ 四個字串零命中;送出鈕英文為 `Record trade`,`scrollWidth == clientWidth` |

**額外驗到的**:網路失敗後用**同一把 key** 重送,後端帳本只多一筆(最終 3 筆 = BUY 10 + BUY 5 + SELL 5),冪等契約在真瀏覽器成立。

## 三個要 Yuan 留意的點

### F-A(步驟 11):「新」標記只會出現在成交後**第一個**打開的頁面

`portfolioRevision` 是跨頁共用的 singleton,而 `UI-SPEC.md` §9 明訂清除時機包含「頁面 unmount」,`App.vue` 又是以 `v-if` 切頁(切頁即卸載)。所以:

- 成交後直接停在 Trades → 該列有高亮與「新」(實測 ✅)
- 成交後先去 Positions(有「新」)再切到 Trades → Trades **沒有**「新」(實測)

兩者都符合規格,但步驟 11 的寫法「切到 Positions 與 Trades 頁,確認剛成交那列有高亮與『新』標記」會讓人以為兩頁都該有。走查時請**一頁一筆**:下一筆單看 Positions,再下一筆單看 Trades。

若你認為「兩頁都該保留到下次操作為止」才是想要的行為,那就是規格要改(§9 的 unmount 清除),不是實作的 bug——請明講,我再開 gap-closure。

### F-B(步驟 13):`NETWORK_ERROR` 沒有追蹤 ID,是對的

步驟 13 寫「下方有 error code 與追蹤 ID 一列」。網路失敗的請求從未抵達伺服器,不會有 `meta.traceId`,所以只會顯示 code。這是正確行為,不是缺漏;步驟措辭偏嚴。

### F-C(步驟 14):320px 下整頁會水平滾動,但**不是** ticket 造成的

錯誤區塊本身完全合格(未溢出、正常換行)。溢位來源是 **app shell 的 header**——`avatar`、`header-logout`、`bell-wrap` 在 320px 下把文件撐到 1070px。這幾個元素不屬 Phase 4 範圍(Phase 4 只動 OrderTicket、三個 portfolio 頁與 services),屬既有的 RWD 缺口。

步驟 14 該判定的「code 與追蹤 ID 完整換行」是 ✅;「沒有水平滾動」若嚴格解讀則 ❌,但責任在 header。建議把它記成獨立的 RWD todo,不要卡住 Phase 4 收尾。

## 你要跑的部分

環境已經起好(若已關機就重跑一次):

```bash
# 後端 + infra(在前端 vue-app/ 下)
E2E_ENV_ONLY=1 E2E_BACKEND_DIR=../../../java/stock-web-v2 bash e2e/run-e2e.sh

# 甲 mock mode
npm run dev

# 乙 API mode(現在可用了)
VITE_DATA_MODE=api npm run dev
```

預檢用的帳號:`precheck@example.com` / `Password1`(已有 2 筆 BUY、1 筆 SELL 的 AAPL 紀錄)。要乾淨資料就自己註冊一個新帳號。

剩下要你判斷的,就是自動化看不到的那些:版面比例、動畫觀感、文案在實際版位下的可讀性,以及雙語切換後的整體視覺。
