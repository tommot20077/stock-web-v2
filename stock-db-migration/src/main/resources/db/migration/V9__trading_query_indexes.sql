-- 交易查詢排序索引（對應決策 D-06 金額排序、D-07 預設排序改以 executed_at 為準）。
-- V7 既有索引全部建在 created_at（入帳時間）上，但 GET /api/v1/trades 的預設排序與日期
-- 篩選改以 executed_at（成交時間）為準，兩者在補登舊交易時會分歧，故需本組索引配套。
-- 所有索引皆以 user_id 開頭，配合恆存在的 user_id WHERE 條件維持使用者隔離下的可用性。

-- D-07：預設排序 executed_at DESC, id DESC 的配套索引（id 為 tie-breaker，一併納入避免額外排序）。
CREATE INDEX idx_transactions_user_executed ON transactions (user_id, executed_at DESC, id DESC);

-- D-06：金額（數量 × 單價）排序的 PostgreSQL 運算式索引；運算式必須加括號。
CREATE INDEX idx_transactions_user_amount ON transactions (user_id, (quantity * price) DESC, id DESC);

-- 取捨說明：sort=quantity 刻意不建索引。per-user 交易筆數量級小（單一使用者的交易列表），
-- 在 user_id 索引過濾後的排序成本可忽略；三個排序鍵全建索引會讓每筆交易寫入都要維護
-- 三份索引，寫入成本與收益不成比例。若日後單一使用者交易量顯著成長再補建即可。
