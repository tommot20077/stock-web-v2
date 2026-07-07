# 判斷準則(Judgment Rubrics)

> 本檔是「不可自動推導的專案判斷」外化。每條附判準與一正例一反例。
> 讀者是任何等級的模型:規則具體、可執行,遇到模糊地帶以本檔為準。
> 證據來源:`.planning/research/PITFALLS.md`、`ai-docs/security.md`、`ai-docs/browser-auth-contract.md`、`.planning/PROJECT.md`。

## 1. 交易語義:這是「已成交紀錄」,不是「下單系統」

**規則**:`POST /api/v1/trades` 建立的是一筆立即成交的手動交易紀錄,後端沒有委託單生命週期。(來源:PITFALLS.md 風險 5、PROJECT.md Out of Scope)

**判準**:
- API payload 只允許 `CreateTradeRequest` 現有欄位(symbol/type/quantity/price/fee/note/executedAt)。
- UI 文案與測試斷言不得出現 pending、cancel、routing、partial fill、TIF 等承諾。

**正例**:OrderTicket 在 API mode 送出 `{symbol, type, quantity, price}`,成功後顯示「交易已記錄」並 refetch portfolio。
**反例**:把前端 mock 的 `ordType`/`tif`/`orderId` 欄位塞進 API payload,或 UI 顯示「委託已送出,等待成交」。

## 2. 瀏覽器 Auth 鐵律:token 不落 JS、unsafe 必帶 CSRF、fail-closed

**規則**(來源:browser-auth-contract.md、security.md §5/§8):
- 瀏覽器模式下 access/refresh token 只存在 HttpOnly cookie;**絕不**存 localStorage/sessionStorage/Pinia/任何 JS 可讀狀態。
- 瀏覽器 cookie 認證的 `POST/PUT/PATCH/DELETE` 必帶 `X-XSRF-TOKEN` header + `credentials: "include"`。
- Redis 不可用時 auth **fail-closed**(503 `AUTH_REDIS_UNAVAILABLE`),不得降級放行。
- Browser login/register/refresh 回應 JSON **不得**含 `data.accessToken` / `data.refreshToken`。

**正例**:`apiClient.ts` 統一注入 credentials 與 CSRF header,401 時做一次 refresh 重試後放棄。
**反例**:「測試環境 Redis 沒起來,先讓 auth 直接通過」——這是安全事故,不是權宜。

## 3. mock/api 雙模式:mock 是明確選擇,不是 fallback

**規則**(來源:PITFALLS.md 風險 4):
- `VITE_DATA_MODE` 無效值在 integration/CI/production 情境必須 fail fast,不得靜默退回 mock。
- 元件不得 import mock store;一律經 `services/` 的 domain service interface,由 `pageApiClients.ts` 依 mode 選實作。
- API mode 的功能驗證必須看到真實 network calls / backend logs,「畫面看起來正常」不算證據。

**正例**:PR 驗證清單含 `VITE_DATA_MODE=api npm test && npm run build`。
**反例**:CI 綠了就宣稱 API 整合完成,但 CI 只跑過 mock mode。

## 4. 信封權威:`ApiResponse<T>` 說了算

**規則**:REST 回應信封的權威是後端 `stock-common` 的 `ApiResponse<T>`(`{success, data, error, meta}`,含 `meta.traceId`)。這是 PROJECT.md Constraints 明定的。
前端 repo 的 `docs/api-contracts/mock-to-real-contract.md` 內 Common API Conventions 一節寫的 `{data, requestId}` 是**早期草案,與後端不一致**——遇到衝突以後端為準,並把發現回報 Yuan、更新文件,不可靜默遷就任一邊。

**正例**:發現 paginated adapter 期待 `{data, page, requestId}` 與後端不合 → 停下,回報差異與兩邊 file:line,提對齊方案。
**反例**:為了讓測試過,在 adapter 裡偷偷做兩種 shape 的兼容解析,不告訴任何人。

(此裁決係由 PROJECT.md Constraints 推斷,Yuan 尚未逐字確認;若出現「前端信封才是新方向」的訊號,先問再裁——見 letter-to-future-sessions.md 信心最低 #3。)

## 5. 交易寫入必須 server-side 冪等

**規則**(來源:PITFALLS.md 風險 6):duplicate submission 防護在後端(`user_id + idempotency key` 唯一約束,duplicate 回既有交易、不重複更新 holdings)。前端 debounce/disabled button 只是 UX,不是防護。

**正例**:timeout 重送沿用同一 idempotency key;新意圖才換 key。
**反例**:「前端已經擋連點了」就不做後端冪等。

## 6. Ownership 失敗回 404,不是 403

**規則**(來源:security.md §4):Service 層用 `SecurityUtils.assertOwnerOrAdmin(...)`(規範類別,程式碼中可能尚未實作——先 grep 確認,不存在就在 `stock-common` 建立,勿另造重複品);失敗丟 `ResourceNotFoundException`(訊息只含資源類型名,絕不含 ID/路徑),避免洩漏資源存在性。`AccessDeniedException` 必須 re-throw 讓 Spring Security 回 403。Controller 層不做 ownership 檢查。

**正例**:查別人的 portfolio → 404 "Portfolio"。
**反例**:回 403「你無權存取 portfolio #123」——同時洩漏了存在性與 ID。

## 7. 高頻計算走 Redis,低頻直寫 DB

**規則**(來源:code-standards.md):價格事件驅動的計算(估值/ROI/dashboard)→ 先寫 Redis、批次回寫 DB、API 只讀 Redis。`holdings`/`transactions`/`assets`/`users` 低頻 → 直寫 DB。Facade 呼叫每請求 ≤3 次,超過改批次或預計算。

**正例**:portfolio 估值查 `cache:portfolio:{userId}:{assetId}`。
**反例**:price tick 進來就 UPDATE DB 的估值表,或 API 為了「即時」直接對 DB 算總市值。

## 8. 何時算「真完成」

一項工作宣稱完成前,必須全部成立:
1. 對應層級測試綠——層級要求與豁免以 testing-standards.md 為準(unit/web/IT 皆必要;E2E 豁免只有 Yuan 能給)。
2. 跨 repo 變更時,兩邊驗證都跑過:backend `./mvnw test`(或 focused `-pl` 模組);frontend `cd ../../vue/stock-v2/vue-app && npm test && npm run build`(Git Bash 語法),涉 API mode 加 `VITE_DATA_MODE=api`。
3. read-back:程式碼改動自己重讀即可;制度/文件類產出派 fresh-context subagent 讀(分工見 model-dispatch.md §5)。
4. 宣稱行為有證據(測試輸出/log/檔案內容),不是「應該可以」。

**反例**:「編譯過了」「畫面正常」「理論上沒問題」都不是完成。

## 9. 何時停下來問 Yuan

遇到以下任一情況,停止動手,帶著證據與選項問:
- 要變更安全語義(cookie/CSRF/權限/token 生命週期)或 API 契約 shape。
- 要刪除或覆寫非自己產生的檔案/資料。
- 工作觸及 PROJECT.md 的 Out of Scope(broker 整合、委託單生命週期、AI 交易策略等)。
- 兩份權威文件矛盾且本檔沒有裁決規則。
- GSD 生成檔(AGENTS.md、`.planning/codebase/*`)一律**禁止手改**(走 maintenance-protocol 同步流程,這不用問);其餘機器/設定檔(`.git/info/exclude`、CI、`.claude/hooks`)動前先問。

## 10. 方向錯了的訊號(換路,別重試)

- 同一個測試修了兩輪還是紅 → 停,重讀需求與測試意圖,可能是理解錯了。
- 開始想「改測試來遷就實作」→ 幾乎必然方向錯(除非測試本身被證明寫錯,那要先回報)。
- 需要繞過 `GlobalExceptionHandler`/`SecurityConfig`/`apiClient.ts` 這些唯一邊界才能達成 → 設計錯了,不是邊界錯了。
- 想手改 GSD 生成檔「先讓它動起來」→ 停,走 `ai-docs/maintenance-protocol.md` 的同步流程。

## 11. 品質底線怎麼驗

- 驗證不自驗:改動者不能只憑自己的推理宣稱正確;用測試、fresh read-back、或第二意見(見 `ai-docs/model-dispatch.md`)。
- 安全相關變更(auth/CSRF/權限/信封)至少要有一個 negative test(該擋的真的擋掉)。
- 測試輸出保持乾淨(pristine output),除非測的就是錯誤處理。
