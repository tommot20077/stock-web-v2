-- 交易寫入的冪等鍵（對應 Phase 4 決策 D-08、judgment.md §5「交易寫入必須 server-side 冪等」）。
--
-- ── 版本號說明 ──────────────────────────────────────────────────────────────
-- Phase 4 的計畫文件寫的是 V10，但 V10 已於 develop 的 d76f824（「還原被 PR #15 改動的
-- V9，新增 V10 補上差異」）占用。已套用的版本號不可重用，故本檔順延為 V11。
-- 索引名稱 uk_transactions_user_idempotency 不受影響，應用層的 ON CONFLICT 推斷照舊。
--
-- ── 為什麼是 transactions 的欄位，而不是獨立表 + 清理 job ───────────────────
-- 獨立的冪等記錄表需要 TTL 清理，否則無限增長；但一旦清掉，同一個 key 重送就會再建出
-- 一筆重複交易——而 transactions 是 append-only 帳本（V8 三個 trigger 禁 UPDATE/DELETE），
-- 重複的交易列永遠改不回來，只能靠反向交易「補」，成本基礎與已實現損益都會被污染。
-- 把 key 存在交易列本身則是「保留期 = 交易保留期」，語意上不可能過期。
-- 同理不用 Redis：Redis 指令與資料庫交易不是原子的，鎖成功但 DB 回滾會讓那個 key 被
-- 永久占掉，使用者重試永遠失敗；而 judgment.md §5 明文要求的就是「唯一約束」。
--
-- ── 為什麼可以對 append-only 表做 ALTER ────────────────────────────────────
-- V8 的三個 trigger（trg_transactions_no_update / no_delete / no_truncate）都是 row 或
-- statement 層的 DML trigger，只在 UPDATE / DELETE / TRUNCATE 時觸發。ALTER TABLE 與
-- CREATE INDEX 屬 DDL，不經過它們，因此本檔不會被擋，也不會削弱 append-only 保證。
-- （TransactionsAppendOnlyIT 三條測試作為本檔的回歸保護，仍必須全綠。）
--
-- ── 為什麼欄位可為 NULL ─────────────────────────────────────────────────────
-- 本檔之前寫入的交易列沒有冪等鍵，也無法回填（沒有任何來源能重建當初的 key，而且
-- append-only 表不允許 UPDATE，就算想填也填不進去）。故欄位必須 nullable，且唯一約束
-- 必須放過 NULL——這正是下面採用「部分索引」的原因。
--
-- ── 建索引刻意採阻塞式，不用 PostgreSQL 的非阻塞建索引模式 ─────────────────
-- 詳細實測紀錄見 V10 檔頭：在 Flyway 底下，非阻塞模式必須等待所有並行交易的 virtualxid
-- 釋放，而 Flyway 自己有一條連線持有 schema history 並停在 idle in transaction，兩者互等，
-- 實測卡死逾 1.5 小時（Postgres 16 + Flyway 11）。此處資料量下短暫鎖表是正確取捨；
-- 若日後 transactions 成長到不能承受，正確做法是部署前在線上先手動建好該索引。

-- 長度 128（決策 DP-4）：必須有上限也必須夠寬。
--   * 無界 TEXT 是 DoS 面（任意長字串會被永久寫進帳本），且 B-tree 索引項有約 2704 bytes 上限，
--     超長 key 會在 INSERT 時炸成難以歸因的錯誤。
--   * VARCHAR(36)（剛好容納 UUID）則太窄：非瀏覽器的 bearer client 可自訂 key 格式，
--     合法但較長的 key 會在 DB 層丟出 DataIntegrityViolationException，而應用層正是以
--     該例外判定「冪等鍵衝突」，會把長度問題誤判成重複請求。
-- 128 同時滿足兩邊，且遠低於索引項上限。
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(128);

-- 冪等的唯一約束。兩個設計點都不是最佳化，而是正確性要求：
--   1. 欄位組合必須是 (user_id, idempotency_key) 兩欄。若寫成單欄 UNIQUE(idempotency_key)，
--      使用者 A 送出的 key 會命中使用者 B 的交易列，導致跨帳戶的資料外洩（威脅 T-04-02）。
--   2. WHERE idempotency_key IS NOT NULL 是必要的部分索引 predicate，理由有二：
--      (a) 放過上述無法回填的既有 NULL 資料；
--      (b) 應用層的 INSERT ... ON CONFLICT (user_id, idempotency_key)
--          WHERE idempotency_key IS NOT NULL 必須以「逐字相同」的 predicate 才推斷得出本索引；
--          若此處省略 WHERE，該語句會直接報
--          「there is no unique or exclusion constraint matching the ON CONFLICT specification」。
--      注意 (a) 單獨看會誤導：PostgreSQL 對多個 NULL 本來就不視為重複，所以就算誤建成
--      非部分索引，「多列 NULL 共存」的測試依然會過——真正抓得到的是直接斷言 indexdef 含 WHERE
--      的那條測試（TransactionsIdempotencyIT#idempotencyIndexIsPartialAndUnique）。
CREATE UNIQUE INDEX uk_transactions_user_idempotency
    ON transactions (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
