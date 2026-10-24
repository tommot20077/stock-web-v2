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
    -- 同一筆原分錄只能被沖銷一次（D-25）。reversal_of_id 為 NULL 的一般分錄不受影響
    -- （唯一約束不視 NULL 為重複）。這同時是 04.1-06 併發雙沖銷的最後防線：
    -- 兩個請求同時沖銷同一筆，應用層即使都通過檢查，第二個 INSERT 也會在這裡被擋下。
    CONSTRAINT uk_cash_ledger_entries_reversal_of UNIQUE (reversal_of_id),
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

-- ── append-only：只允許 INSERT，禁止 UPDATE / DELETE / TRUNCATE（D-09）─────────────
-- 現金分錄與 transactions 同屬需稽核的資金歷史，錯記只能靠沖銷（一筆反向分錄）更正，不能改寫歷史。
-- 刻意新寫通用的 prevent_append_only_mutation()，而不重用 V8 為 transactions 寫的那支專用 function：
-- 那支 function 的錯誤訊息把表名寫死為 transactions，用在本表會讓訊息指向錯的表；
-- 而 V8 已套用到環境，依 flyway-convention 不可修改。這支用 TG_TABLE_NAME 組訊息，
-- 日後其他 append-only 表可直接重用，不必再各寫一支。
CREATE OR REPLACE FUNCTION prevent_append_only_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_cash_ledger_entries_no_update
    BEFORE UPDATE ON cash_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_cash_ledger_entries_no_delete
    BEFORE DELETE ON cash_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

-- TRUNCATE 不經過 row 層 trigger，必須另設 statement 層 trigger 才擋得住。
CREATE TRIGGER trg_cash_ledger_entries_no_truncate
    BEFORE TRUNCATE ON cash_ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_append_only_mutation();

-- ── 沖銷驗證：沖銷分錄必須是原分錄的「精確反向」（D-25）────────────────────────────
-- 既有的 trigger 都只是阻擋異動，本檔這支是 BEFORE INSERT 的驗證型 trigger（repo 內無前例）。
-- 為什麼要在 DB 層而不只在應用層：沖銷 API（04.1-06）會做同樣的檢查，但帳本一旦寫入就改不回來，
-- 任何繞過應用層的寫入（手動 SQL、日後新增的另一條寫入路徑、應用層的 bug）都不該有機會寫進
-- 一筆「沖銷了別人的錢」或「金額對不上」的分錄。規則逐條對應下方的 IF：
--   1. 原分錄必須存在；
--   2. 原分錄必須屬於同一使用者（不得沖銷他人的分錄）；
--   3. 原分錄本身不能是沖銷分錄（不允許沖銷的沖銷，避免帳本出現無限回溯的鏈）；
--   4. 幣別、金額必須相同；
--   5. 方向必須相反（DEPOSIT 對 WITHDRAWAL）。
-- 「同一原分錄只能被沖銷一次」不在這裡檢查，而是交給 uk_cash_ledger_entries_reversal_of：
-- 唯一約束在併發下是原子的，trigger 內的 SELECT 檢查則有競態。
-- 錯誤訊息一律靜態、不含任何欄位值（不回射使用者輸入，也不洩漏他人分錄的存在與內容）。
CREATE OR REPLACE FUNCTION validate_cash_ledger_reversal()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    original cash_ledger_entries%ROWTYPE;
BEGIN
    -- 一般分錄（非沖銷）直接放行。
    IF NEW.reversal_of_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO original FROM cash_ledger_entries WHERE id = NEW.reversal_of_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'cash ledger reversal rejected: original entry does not exist'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.user_id <> NEW.user_id THEN
        RAISE EXCEPTION 'cash ledger reversal rejected: original entry belongs to another user'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.reversal_of_id IS NOT NULL THEN
        RAISE EXCEPTION 'cash ledger reversal rejected: original entry is itself a reversal'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.currency <> NEW.currency OR original.amount <> NEW.amount THEN
        RAISE EXCEPTION 'cash ledger reversal rejected: currency or amount does not match the original entry'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.type = NEW.type THEN
        RAISE EXCEPTION 'cash ledger reversal rejected: type must be opposite to the original entry'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cash_ledger_entries_validate_reversal
    BEFORE INSERT ON cash_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION validate_cash_ledger_reversal();
