-- 現金股利帳本（Phase 04.2，DIV-01；決策 D-01 ~ D-03、D-13）。
--
-- ── 為什麼是新表而不是擴充 transactions 或 cash_ledger_entries ──────────────────────
-- 04.2-02 決策閘門裁決（option-recommended）：股利採獨立的 append-only 表。
--   * 不擴充 transactions.type：transactions 的每一列都會被持倉重算（HoldingCalculator）吃進去，
--     股利不動股數與平均成本（D-01），塞進去等於讓每一條持倉計算路徑都要學會「跳過這種列」。
--   * 不擴充 cash_ledger_entries：股利必須掛標的（asset_id）才能算各標的的股利收入，
--     且帶預扣稅欄位；入出金沒有這兩個維度，硬塞會讓兩種語意共用一組放寬過的 CHECK。
--
-- ── 為什麼不存 currency ─────────────────────────────────────────────────────
-- 股利的幣別就是標的的交易幣別（契約：幣別不由請求指定）。存一份副本只會在標的幣別被修正時
-- 產生兩個不一致的真相；應用層一律經 AssetFacade 取標的幣別（trading 模組不得 JOIN assets）。
--
-- ── 沖銷列的值與原列相同 ────────────────────────────────────────────────────
-- amount 與 withholding_tax 恆為正（CHECK），沖銷列存的是與原列「相同」的正值，
-- 方向由 reversal_of_id IS NOT NULL 表達。所有聚合（可用現金、dividendIncome、dividendTaxWithheld）
-- 必須以 reversal_of_id IS NOT NULL 反號，不可直接 SUM(amount)。
--
-- ── 版本號說明 ──────────────────────────────────────────────────────────────
-- 同 wave 的 04.2-07 擁有 V17、04.2-11 擁有 V18；三者各自新增不同版號的新檔、不共用任何檔案，
-- Flyway 依版號排序套用，CI 每次以全新容器從 V1 套用。
--
-- ── 本檔的完整範圍 ──────────────────────────────────────────────────────────
-- 表、索引、append-only trigger 與沖銷驗證 trigger 全部在本檔完成；其他 plan（含 04.2-04 的沖銷 API）
-- 不得再修改本檔。append-only 重用 V15 定義的通用函式 prevent_append_only_mutation()，本檔不重新定義。

CREATE TABLE cash_dividends (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID           NOT NULL DEFAULT uuid_generate_v4(),
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    asset_id        BIGINT         NOT NULL REFERENCES assets(id),
    -- 實領總額（扣稅後、標的幣別），恆為正。
    amount          NUMERIC(24, 8) NOT NULL,
    -- 預扣稅（標的幣別），未填為 0；稅前股利 = amount + withholding_tax。
    withholding_tax NUMERIC(24, 8) NOT NULL DEFAULT 0,
    note            VARCHAR(500),
    -- 入帳時間（使用者可補登）。
    paid_at         TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    -- 新表沒有舊列需要回填，冪等鍵直接 NOT NULL + 一般 UNIQUE（理由同 V15）；長度 128 與
    -- transactions / cash_ledger_entries 一致，應用層的長度上限必須與此同步。
    idempotency_key VARCHAR(128)   NOT NULL,
    -- 沖銷 = 一筆值相同、指回原列的紀錄；一般紀錄為 NULL。DB 層規則見下方 trigger 與唯一約束。
    reversal_of_id  BIGINT REFERENCES cash_dividends(id),

    CONSTRAINT uk_cash_dividends_uuid UNIQUE (uuid),
    -- 冪等的唯一約束必須含 user_id：只有 idempotency_key 的話，使用者 A 的 key 會命中使用者 B 的紀錄，
    -- 造成跨帳戶資料外洩（威脅 T-04-02 / T-04.2-06）。
    CONSTRAINT uk_cash_dividends_user_idempotency UNIQUE (user_id, idempotency_key),
    -- 同一筆原列只能被沖銷一次（D-13）。唯一約束不視 NULL 為重複，一般紀錄不受影響；
    -- 這也是併發雙沖銷的最後防線：應用層即使兩個請求都通過檢查，第二個 INSERT 也會在這裡被擋下。
    CONSTRAINT uk_cash_dividends_reversal_of UNIQUE (reversal_of_id),
    CONSTRAINT ck_cash_dividends_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_cash_dividends_tax_non_negative CHECK (withholding_tax >= 0)
);

-- 活動列表（04.2-10）依「入帳時間新到舊，同時間以 id 新到舊」排序分頁，索引與排序鍵逐欄一致。
-- 刻意採一般（阻塞式）建索引：理由見 V11（Flyway 的 idle in transaction 連線會與非阻塞建索引互等而卡死）；
-- 本表剛建立，沒有資料可鎖。
CREATE INDEX idx_cash_dividends_user_paid
    ON cash_dividends (user_id, paid_at DESC, id DESC);

-- summary 的各標的股利收入（04.2-05）依 (user_id, asset_id) 彙總。
CREATE INDEX idx_cash_dividends_user_asset
    ON cash_dividends (user_id, asset_id);

-- ── append-only：只允許 INSERT，禁止 UPDATE / DELETE / TRUNCATE（D-13）──────────────
-- 股利與其他帳本同屬需稽核的資金歷史，錯記只能靠沖銷更正。重用 V15 的 prevent_append_only_mutation()
-- （以 TG_TABLE_NAME 組訊息，錯誤會指向本表），不重新定義、也不修改 V15。
CREATE TRIGGER trg_cash_dividends_no_update
    BEFORE UPDATE ON cash_dividends
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_cash_dividends_no_delete
    BEFORE DELETE ON cash_dividends
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

-- TRUNCATE 不經過 row 層 trigger，必須另設 statement 層 trigger 才擋得住。
CREATE TRIGGER trg_cash_dividends_no_truncate
    BEFORE TRUNCATE ON cash_dividends
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_append_only_mutation();

-- ── 沖銷驗證：沖銷列必須與原列「值相同」（D-13）──────────────────────────────────
-- 骨架同 V15 的 validate_cash_ledger_reversal。沖銷 API（04.2-04）會做同樣的檢查並回前端可分派的錯誤碼，
-- 這支 trigger 是繞過應用層（手動 SQL、日後的另一條寫入路徑、應用層 bug）時的最後防線。規則逐條對應下方的 IF：
--   1. 原列必須存在；
--   2. 原列必須屬於同一使用者（不得沖銷他人的紀錄）；
--   3. 原列必須是同一標的；
--   4. 原列本身不能是沖銷（不允許沖銷的沖銷，避免帳本出現無限回溯的鏈）；
--   5. amount 與 withholding_tax 必須相同（沖銷列存與原列相同的正值，聚合時反號）。
-- 「同一原列只能被沖銷一次」不在這裡檢查，交給 uk_cash_dividends_reversal_of：唯一約束在併發下是原子的，
-- trigger 內的 SELECT 檢查則有競態。
-- 錯誤訊息一律靜態、不含任何欄位值（不回射使用者輸入，也不洩漏他人紀錄的存在與內容）。
CREATE OR REPLACE FUNCTION validate_cash_dividend_reversal()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    original cash_dividends%ROWTYPE;
BEGIN
    -- 一般紀錄（非沖銷）直接放行。
    IF NEW.reversal_of_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO original FROM cash_dividends WHERE id = NEW.reversal_of_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'cash dividend reversal rejected: original dividend does not exist'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.user_id <> NEW.user_id THEN
        RAISE EXCEPTION 'cash dividend reversal rejected: original dividend belongs to another user'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.asset_id <> NEW.asset_id THEN
        RAISE EXCEPTION 'cash dividend reversal rejected: asset does not match the original dividend'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.reversal_of_id IS NOT NULL THEN
        RAISE EXCEPTION 'cash dividend reversal rejected: original dividend is itself a reversal'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.amount <> NEW.amount OR original.withholding_tax <> NEW.withholding_tax THEN
        RAISE EXCEPTION 'cash dividend reversal rejected: amount or withholding tax does not match the original dividend'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cash_dividends_validate_reversal
    BEFORE INSERT ON cash_dividends
    FOR EACH ROW EXECUTE FUNCTION validate_cash_dividend_reversal();
