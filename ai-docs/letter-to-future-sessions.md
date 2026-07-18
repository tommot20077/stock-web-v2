# 給未來 session 的信(2026-07-07)

寫這封信的 session 建立了本 repo 的制度層(judgment / model-dispatch / task-briefs / maintenance-protocol / harness-diagnosis)。以下是沒被問到、但你最需要知道的事。

## 三件沒人問但最重要的事

1. **CLAUDE.md 是本機孤本,這是刻意的**。`.git/info/exclude` 把個人協作層(CLAUDE.md、`.claude/`)排除在公開 repo 外。代價:換機器或磁碟損毀即丟失、無歷史。如果你發現 CLAUDE.md 不見了或內容退化,權威重建材料是:`ai-docs/` 全部 + 本信所在目錄的制度檔。先重建 Guidelines Index 式的薄路由,不要重建厚內容。
2. **這個產品的整合主軸卡在信封與 auth 契約**。動前端 API mode 之前,先讀 `ai-docs/browser-auth-contract.md` 與 `judgment.md` §1-5;PITFALLS 預言過的坑(靜默 mock、冪等、成交後不 refetch)一個都還沒過時,除非對應 phase 的測試已經存在。
3. **兩個 repo 的治理是不對稱的(刻意)**:後端深(GSD+ai-docs+skills)、前端薄(AGENTS.md 路由 + LESSONS)。不要「順手」幫前端裝 GSD 或複製後端全套——那是被否決過的方案(2026-07-07,Yuan 拍板「後端深、前端薄」)。

## 這套制度最可能的退化方式與預防

1. **LESSONS.md 沒人寫 → 飛輪停轉**。預防:task-briefs 的回報格式要求列「殘留風險」,遇到坑順手 append 一行的成本已壓到最低。如果你發現 LESSONS 超過一個月沒新條目而你明明踩了坑——就是退化正在發生,現在就補寫。
2. **CLAUDE.md 再度長肥**。有人(包括你)會想把「這條很重要」塞進常載層。規則:鐵律短句走 PROJECT.md Constraints,其他一律按需檔 + 路由一行。CLAUDE.md >150 行就該拆。
3. **GSD 覆寫手寫內容**。`.planning/codebase/` 整目錄是可重生機器產物,`/gsd-map-codebase` 會覆寫。判斷型內容永遠放 ai-docs 或 PROJECT.md Constraints。
4. **制度檔與現實脫節**。model-dispatch §0 的參數、diagnosis 的行數統計都是 2026-07-07 快照;引用前先查證現值(`.planning/config.json`、`wc -l`)。

## 信心最低的三個產出(誠實條款)

1. **pre-commit hook 是否生效:未驗證**。diagnosis §次要-1 給了驗證法,但本 session 沒跑(不想在制度 PR 裡混入測試 commit)。在它被驗證前,別假設「commit 必跑測試」。
2. **「被自動導向其他型號的請求是否消耗高階額度」:查不到**,計費黑箱。model-dispatch §0 標了「未確認」,不要把它當已知。
3. **信封權威裁決(judgment §4)**:基於 PROJECT.md Constraints 的「continue using ApiResponse<T>」推斷後端為權威。Yuan 沒有逐字確認過這個裁決;如果未來出現「前端信封才是新方向」的訊號,先問再裁。

## 明天怎麼開始用

1. 冷啟動:CLAUDE.md 會把你路由到該讀的東西,按需讀,別全讀。
2. 接到任務:對照 `task-briefs.md` 選範本;判斷模糊處查 `judgment.md`;要派工查 `model-dispatch.md`。
3. 踩坑:一行進 `bug-reports/LESSONS.md`;修完 bug 跑 `post-bug` skill。
4. 改規範:走 `maintenance-protocol.md` §5 checklist。
