# Phase 05 Deferred Items

範圍外發現(依 executor scope boundary 記錄,不在發現的 plan 內修)。

| 發現於 | 項目 | 證據 | 建議 |
|--------|------|------|------|
| 05-01(J0 假後端演練) | API mode 未登入時 `App.vue` 照樣掛載主內容(`showMainContent` 只在 session `error` 時隱藏),Overview 一進站就打 `GET /portfolio/summary`、`/trades`、`/watchlists`,401 → refresh 失敗後各區塊停在「讀取失敗 `AUTH_REFRESH_TOKEN_INVALID`」。UI 登入後已掛載的頁面**不會自動重讀**(`watch(sessionState.status)` 只重連報價串流),使用者要切頁才看得到資料。屬既有 UX 缺陷,非 05-01 造成 | 前端 `vue-app/src/App.vue:222-223`、`:297-301`;J0 第一次演練失敗於 `overview-kpi` 不可見,頁面快照為三個 `AUTH_REFRESH_TOKEN_INVALID` 錯誤區塊 | 二擇一:(1) 未登入時不掛載需授權的頁面;(2) session 轉 `authenticated` 時通知頁面重讀(例如 bump `portfolioRevision`)。修好後 J0「交易前讀 Overview 與 Positions」的切頁繞道可改回直接斷言 |
