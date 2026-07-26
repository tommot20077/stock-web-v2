-- 交易查詢排序索引（對應決策 D-06 金額排序、D-07 預設排序改以 executed_at 為準）。
-- V7 既有索引全部建在 created_at（入帳時間）上，但 GET /api/v1/trades 的預設排序與日期
-- 篩選改以 executed_at（成交時間）為準，兩者在補登舊交易時會分歧，故需本組索引配套。
-- 所有索引皆以 user_id 開頭，配合恆存在的 user_id WHERE 條件維持使用者隔離下的可用性。
--
-- ── 鎖的取捨：本檔刻意「不」使用 CONCURRENTLY ──────────────────────────────────
-- 一般的 CREATE INDEX 會對 transactions 取 ACCESS EXCLUSIVE 鎖，在 Flyway 套用本檔的
-- 期間擋住所有 INSERT。直覺的解法是改用 CREATE INDEX CONCURRENTLY，但在 Flyway 底下
-- 這會直接死鎖：Flyway 套用 migration 時另有一條連線持有 schema history 的交易並停在
-- idle in transaction，而 CONCURRENTLY 必須等待所有並行交易的 virtualxid 釋放，兩者互等。
-- 實測（TradingApiIT，Postgres 16 + Flyway 11）：V9 已被正確判定為 [non-transactional]
-- 並在交易外執行，但仍卡死逾 1.5 小時，pg_stat_activity 顯示
--   pid A  idle in transaction  ← Flyway schema history
--   pid B  active  Lock/virtualxid  ← 本檔的 CREATE INDEX CONCURRENTLY
-- 亦即在 migration 內使用 CONCURRENTLY 會讓「短暫鎖表」惡化成「啟動永遠不會完成」。
--
-- 因此本檔採一般 CREATE INDEX。transactions 資料量仍小時，啟動期的短暫鎖表可接受。
-- 當該表成長到不能承受啟動鎖表時，正確做法是「部署前先在線上手動建好」：
--
--   CREATE INDEX CONCURRENTLY idx_transactions_user_executed
--       ON transactions (user_id, executed_at DESC, id DESC);
--   CREATE INDEX CONCURRENTLY idx_transactions_user_asset_executed
--       ON transactions (user_id, asset_id, executed_at DESC, id DESC);
--   CREATE INDEX CONCURRENTLY idx_transactions_user_amount
--       ON transactions (user_id, (quantity * price) DESC, id DESC);
--   DROP INDEX CONCURRENTLY IF EXISTS idx_transactions_user_asset_created;
--
-- 本檔所有述句都帶 IF NOT EXISTS / IF EXISTS，屆時本 migration 會自然變成 no-op。
-- （手動建立前請先確認沒有殘留的 INVALID 索引：
--   SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;）

-- D-07：預設排序 executed_at DESC, id DESC 的配套索引（id 為 tie-breaker，一併納入避免額外排序）。
CREATE INDEX IF NOT EXISTS idx_transactions_user_executed
    ON transactions (user_id, executed_at DESC, id DESC);

-- D-05：symbol 篩選 + 預設排序的配套索引。symbol 會被解析成 asset_id 進入 WHERE，排序仍是
-- executed_at；缺此索引時 PostgreSQL 只能先以 asset_id 過濾再自行排序。
CREATE INDEX IF NOT EXISTS idx_transactions_user_asset_executed
    ON transactions (user_id, asset_id, executed_at DESC, id DESC);

-- D-06：金額（數量 × 單價）排序的 PostgreSQL 運算式索引；運算式必須加括號。
CREATE INDEX IF NOT EXISTS idx_transactions_user_amount
    ON transactions (user_id, (quantity * price) DESC, id DESC);

-- V7 的 (user_id, asset_id, created_at DESC, id DESC) 在本 phase 之後已無任何讀者：symbol
-- 篩選的排序改由上面的 idx_transactions_user_asset_executed 承接。留著只會讓每筆交易寫入
-- 多維護一份沒有人讀的索引。
-- 同組的 idx_transactions_user_created 則「保留」：sort=createdAt 仍以它為配套索引。
DROP INDEX IF EXISTS idx_transactions_user_asset_created;

-- 取捨說明：sort=quantity 刻意不建索引。per-user 交易筆數量級小（單一使用者的交易列表），
-- 在 user_id 索引過濾後的排序成本可忽略；每個排序鍵都建索引會讓每筆交易寫入都要維護更多份
-- 索引，寫入成本與收益不成比例。若日後單一使用者交易量顯著成長再補建即可。
