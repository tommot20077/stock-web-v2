-- 現金分錄帳本（Phase 04.1，CASH-01；決策 D-06 ~ D-11、D-25）。
--
-- ── 為什麼是「分錄表」而不是 users 上的餘額欄位 ──────────────────────────────
-- 入金 / 出金是需要稽核的資金歷史，與 transactions 同屬 append-only 帳本：餘額一律由分錄
-- 推導（CASH-02），不存可被覆寫的餘額欄位。這也是 D-01 / D-08「只記帳、不驗證餘額」能成立
-- 的前提——帳本不擋任何一筆分錄，餘額為負只是「使用者還沒補記入金」的如實呈現。
--
-- ── 版本號說明 ──────────────────────────────────────────────────────────────
-- 同 wave 的 04.1-02 擁有 V14（users.base_currency），本檔為 V15。兩者各自新增不同版號的新檔，
-- 不共用任何檔案；Flyway 依版號排序套用，CI 每次以全新容器從 V1 套用。
--
-- ── 本檔的完整範圍 ──────────────────────────────────────────────────────────
-- 表、索引、append-only trigger 與沖銷驗證 trigger 全部在本檔完成（V15 尚未合併、未套用到任何
-- 持久環境前屬同一份 migration 的完成）。其他 plan（含 04.1-06 的沖銷 API）不得再修改本檔。

CREATE TABLE cash_ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID           NOT NULL DEFAULT uuid_generate_v4(),
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    -- 04.2 擴充點：股利入帳可擴充 type 白名單，並加上 nullable 的 asset_id 欄位；
    -- 擴充只需新 migration 放寬下方 CHECK，不必改動既有列。
    type            VARCHAR(20)    NOT NULL,
    -- 與 stock-common 的 CurrencyCode 同步；新增幣別時兩處必須一起改。
    currency        VARCHAR(10)    NOT NULL,
    amount          NUMERIC(24, 8) NOT NULL,
    note            VARCHAR(500),
    occurred_at     TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    -- 新表沒有舊列需要回填，所以冪等鍵可以直接 NOT NULL + 一般 UNIQUE；
    -- 不必照抄 V11 的部分索引（V11 的 WHERE predicate 必須與應用層 ON CONFLICT 逐字一致，
    -- 本表沒有 predicate，這個地雷一併消失）。長度 128 與 transactions 一致，理由見 V11。
    idempotency_key VARCHAR(128)   NOT NULL,
    -- 沖銷 = 一筆反向分錄（D-25）；指向被沖銷的原分錄。一般分錄為 NULL。
    -- 沖銷規則的 DB 層強制見本檔下方的 trigger 與 uk_cash_ledger_entries_reversal_of。
    reversal_of_id BIGINT REFERENCES cash_ledger_entries(id),

    CONSTRAINT uk_cash_ledger_entries_uuid UNIQUE (uuid),
    -- 冪等的唯一約束，欄位組合必須含 user_id：若只有 idempotency_key，
    -- 使用者 A 的 key 會命中使用者 B 的分錄，造成跨帳戶資料外洩（威脅 T-04-02 / T-04.1-11）。
    CONSTRAINT uk_cash_ledger_entries_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_cash_ledger_entries_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_cash_ledger_entries_currency CHECK (currency IN ('USD', 'TWD', 'EUR', 'JPY')),
    -- 金額恆為正，方向由 type 表達（與 transactions 的 quantity > 0 同風格）；
    -- 沖銷因此不是負數分錄，而是一筆方向相反、金額相同的分錄。
    CONSTRAINT ck_cash_ledger_entries_amount_positive CHECK (amount > 0)
);

-- 列表（04.1-06）依「發生時間新到舊，同時間以 id 新到舊」排序並分頁，索引與排序鍵逐欄一致，
-- 讓每頁查詢走索引掃描而不是排序整個使用者的帳本。
-- 刻意採一般（阻塞式）建索引、不用非阻塞建索引模式：理由見 V11（Flyway 的 idle in transaction
-- 連線會與非阻塞建索引互等而卡死）；本表剛建立，沒有資料可鎖。
CREATE INDEX idx_cash_ledger_entries_user_occurred
    ON cash_ledger_entries (user_id, occurred_at DESC, id DESC);
