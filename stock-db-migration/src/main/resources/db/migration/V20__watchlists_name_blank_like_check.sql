-- 觀察清單名稱「看似空白」的 DB 兜底（Phase 5 D-02b；源自 quick-261107-1j4 / 04.2 REVIEW IN-04）。
--
-- ── 為什麼 ──────────────────────────────────────────────────────────────────
-- V18 的 ck_watchlists_name_not_blank 是 btrim(name) <> ''，預設只去除 ASCII 空白（U+0020）；
-- 只含不換行空白（U+00A0 / U+2007 / U+202F）或全形空白（U+3000）的名稱能通過 CHECK。
-- 服務層 WatchlistService.normalizeName 自 quick-261107-1j4 起已把這類名稱視為空白（400 fields.name），
-- 本 migration 讓 DB 以同一個字元集兜底，擋住繞過應用層（直接 SQL、手動修資料、未來的匯入工具）的寫入。
-- 名稱中間的不換行空白照常允許（btrim 只去除兩端）。
--
-- ── 字元集與 Java 的同步 ────────────────────────────────────────────────────
-- 下方 E-string 的 28 個字元 = Java 21 的 Character.isWhitespace(cp) || Character.isSpaceChar(cp)
-- 對全部 code point 掃描的結果（2026-11-09 以 JDK 21.0.12 實際列舉核對）：
--   U+0009 U+000A U+000B U+000C U+000D U+001C U+001D U+001E U+001F   （isWhitespace 的控制字元）
--   U+0020 U+00A0 U+1680 U+2000-U+200A U+2028 U+2029 U+202F U+205F U+3000   （Zs / Zl / Zp 與空白）
-- 服務層判斷式或 JDK 的 Unicode 版本若改變字元集，要以新的 migration（V21+）重建本約束，
-- 並同步 WatchlistService.normalizeName 的 Javadoc 與 ai-docs/watchlist-contract.md。
-- E-string 的 \uXXXX 需要資料庫編碼為 UTF8（05-RESEARCH Assumption A7；CI 的 Testcontainers 與部署 DB 皆為 UTF8）；
-- 若某環境非 UTF8 而套用失敗，改以 chr(160) 等函式串接字元集，寫在新的 migration。
--
-- ── 套用失敗時 ──────────────────────────────────────────────────────────────
-- ADD CONSTRAINT 會掃描既有列；quick-261107-1j4 之前服務層允許只含不換行空白的名稱，若某環境有這類列，
-- 本 migration 會失敗。以同一個 btrim 運算式找出違規列：
--   select id, user_id, name from watchlists
--   where btrim(name, E'\u0009\u000A\u000B\u000C\u000D\u001C\u001D\u001E\u001F\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000') = '';
-- 修正（改名或刪除）後再重跑。
--
-- 不使用 CONCURRENTLY（CHECK 約束沒有 CONCURRENTLY 形式；watchlists 為小表）。V1~V19 不得修改；
-- 一經套用到共用環境，只能以新的 migration 調整本約束。
ALTER TABLE watchlists DROP CONSTRAINT ck_watchlists_name_not_blank;

ALTER TABLE watchlists ADD CONSTRAINT ck_watchlists_name_not_blank CHECK (
    btrim(name, E'\u0009\u000A\u000B\u000C\u000D\u001C\u001D\u001E\u001F\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000') <> ''
);
