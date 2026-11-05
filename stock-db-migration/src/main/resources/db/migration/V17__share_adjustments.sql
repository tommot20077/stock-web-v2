-- 股數調整帳本：配股（股票股利）與拆股 / 合股（Phase 04.2，ADJ-01；決策 D-04 ~ D-09、D-13）。
--
-- ── 為什麼是新表而不是擴充 transactions ────────────────────────────────────────
-- 04.2-02 決策閘門裁決（option-recommended）：股數調整採獨立的 append-only 表。
--   * transactions 的每一列都是「有價格的成交」，持倉計算（HoldingCalculator.applyBuy / applySell）、
--     已實現損益、交易現金流都直接吃它；配股 / 拆股沒有成交價、不動總成本也不產生現金流，
--     塞進 transactions.type 等於讓每一條讀 transactions 的路徑都要學會「跳過這種列」。
--   * 股數調整不得經 POST /api/v1/trades 寫入（judgment §1；D-11 入口與下單分開）。
--
-- ── effective_date 與 effective_at 並存 ────────────────────────────────────────
-- effective_date 是使用者輸入的除權日（該標的市場的日曆日），是冪等比對與顯示的依據；
-- effective_at 是後端以 TradingDay.of(assetType, market).startOf(effective_date) 換算出的生效瞬間
-- （股票為交易所時區午夜、外匯紐約 17:00、加密 UTC 00:00），是排序、時序規則與「除權日起點持股」切點的依據。
-- 只存日期的話，每個讀者都得各自重算時區（美股除權日會被台北午夜算到前一個紐約交易日）；
-- 只存瞬間的話，使用者輸入的日期在換算規則改變後就無法還原。兩者都存，換算只發生在寫入一處。
--
-- ── quantity_delta 由後端算出、帶號 ────────────────────────────────────────────
-- 配股 = 實領股數（恆正）；拆股 / 合股 = 除權日起點持股 × ratio_new / ratio_old（scale 8、DOWN）− 除權日起點持股
-- （合股為負）。存下來而不是讀時重算：重算需要當時的除權日起點持股，而那會隨補登交易改變。
--
-- ── 沖銷撤銷已記錄的 ΔQ，聚合以 reversal_of_id 反號 ──────────────────────────────
-- 沖銷列存與原列「相同」的值（含 quantity_delta、effective_date、effective_at），方向由
-- reversal_of_id IS NOT NULL 表達。沖銷的語意是「這個事件從未發生」：撤銷已記錄的 quantity_delta、
-- 不重算比例。所有聚合（除權日起點持股、活動列表）必須以 reversal_of_id IS NOT NULL 反號，不可直接 SUM。
--
-- ── 版本號說明 ──────────────────────────────────────────────────────────────
-- 同 wave 的 04.2-03 擁有 V16、04.2-11 擁有 V18；三者各自新增不同版號的新檔、不共用任何檔案，
-- Flyway 依版號排序套用，CI 每次以全新容器從 V1 套用。
--
-- ── 本檔的完整範圍 ──────────────────────────────────────────────────────────
-- 表、索引、append-only trigger 與沖銷驗證 trigger 全部在本檔完成；其他 plan（含 04.2-08 的沖銷 API）
-- 不得再修改本檔。append-only 重用 V15 定義的通用函式 prevent_append_only_mutation()，本檔不重新定義。

CREATE TABLE share_adjustments (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID           NOT NULL DEFAULT uuid_generate_v4(),
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    asset_id        BIGINT         NOT NULL REFERENCES assets(id),
    -- STOCK_DIVIDEND（配股）或 SPLIT（拆股 / 合股）。
    type            VARCHAR(20)    NOT NULL,
    -- 比例 new:old（例：1 拆 4 = 4:1；10 合 1 = 1:10），僅 SPLIT 有值。
    ratio_new       INTEGER,
    ratio_old       INTEGER,
    -- 後端算出的股數變動，帶號（合股為負）。
    quantity_delta  NUMERIC(24, 8) NOT NULL,
    -- 除權日（該標的市場的日曆日，使用者輸入）。
    effective_date  DATE           NOT NULL,
    -- 生效瞬間（除權日該市場交易日的起點，後端換算）。
    effective_at    TIMESTAMPTZ    NOT NULL,
    note            VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    -- 新表沒有舊列需要回填，冪等鍵直接 NOT NULL + 一般 UNIQUE（理由同 V15）；長度 128 與
    -- transactions / cash_ledger_entries / cash_dividends 一致，應用層的長度上限必須與此同步。
    idempotency_key VARCHAR(128)   NOT NULL,
    -- 沖銷 = 一筆值相同、指回原列的紀錄；一般紀錄為 NULL。DB 層規則見下方 trigger 與唯一約束。
    reversal_of_id  BIGINT REFERENCES share_adjustments(id),

    CONSTRAINT uk_share_adjustments_uuid UNIQUE (uuid),
    -- 冪等的唯一約束必須含 user_id：只有 idempotency_key 的話，使用者 A 的 key 會命中使用者 B 的紀錄。
    CONSTRAINT uk_share_adjustments_user_idempotency UNIQUE (user_id, idempotency_key),
    -- 同一筆原列只能被沖銷一次（D-13）；併發雙沖銷的最後防線（唯一約束在併發下是原子的）。
    CONSTRAINT uk_share_adjustments_reversal_of UNIQUE (reversal_of_id),
    CONSTRAINT ck_share_adjustments_type CHECK (type IN ('STOCK_DIVIDEND', 'SPLIT')),
    -- 形狀依 type 而定：配股沒有比例且 ΔQ 恆正；拆股 / 合股兩個比例皆正、不相等，ΔQ 非零（合股為負）。
    -- 兩個刻意的寫法：
    --   * SPLIT 分支明寫 ratio_new / ratio_old IS NOT NULL：CHECK 對 NULL 結果視為通過，只寫 ratio_old > 0 的話
    --     缺比例的列會得到 NULL 而被放行。
    --   * 用 CASE 且未知 type 回 TRUE：PostgreSQL 依約束名稱字母序檢查 CHECK，shape 排在 type 之前；
    --     若 shape 對未知 type 回 false，違規訊息會指向 shape 而不是真正的原因。未知 type 交給 ck_share_adjustments_type。
    CONSTRAINT ck_share_adjustments_shape CHECK (
        CASE type
            WHEN 'STOCK_DIVIDEND' THEN
                ratio_new IS NULL AND ratio_old IS NULL AND quantity_delta > 0
            WHEN 'SPLIT' THEN
                ratio_new IS NOT NULL AND ratio_old IS NOT NULL
                AND ratio_new > 0 AND ratio_old > 0 AND ratio_new <> ratio_old AND quantity_delta <> 0
            ELSE TRUE
        END
    )
);

-- 除權日起點持股（quantityAsOf）與「該標的最晚未沖銷調整」（findLatestActive）依
-- (user_id, asset_id) 篩選、以 effective_at DESC, id DESC 排序。刻意採一般（阻塞式）建索引：理由見 V11；
-- 本表剛建立，沒有資料可鎖。
CREATE INDEX idx_share_adjustments_user_asset_effective
    ON share_adjustments (user_id, asset_id, effective_at DESC, id DESC);

-- 活動列表（04.2-10）依生效時間新到舊、同時間以 id 新到舊排序分頁，索引與排序鍵逐欄一致。
CREATE INDEX idx_share_adjustments_user_effective
    ON share_adjustments (user_id, effective_at DESC, id DESC);

-- ── append-only：只允許 INSERT，禁止 UPDATE / DELETE / TRUNCATE（D-13）──────────────
-- 重用 V15 的 prevent_append_only_mutation()（以 TG_TABLE_NAME 組訊息，錯誤會指向本表），不重新定義。
CREATE TRIGGER trg_share_adjustments_no_update
    BEFORE UPDATE ON share_adjustments
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_share_adjustments_no_delete
    BEFORE DELETE ON share_adjustments
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

-- TRUNCATE 不經過 row 層 trigger，必須另設 statement 層 trigger 才擋得住。
CREATE TRIGGER trg_share_adjustments_no_truncate
    BEFORE TRUNCATE ON share_adjustments
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_append_only_mutation();

-- ── 沖銷驗證：沖銷列必須與原列「值相同」（D-13）──────────────────────────────────
-- 骨架同 V16 的 validate_cash_dividend_reversal。沖銷 API（04.2-08）會做同樣的檢查並回前端可分派的錯誤碼，
-- 這支 trigger 是繞過應用層時的最後防線。規則逐條對應下方的 IF：
--   1. 原列必須存在；
--   2. 原列必須屬於同一使用者；
--   3. 原列必須是同一標的；
--   4. 原列本身不能是沖銷；
--   5. type、ratio_new、ratio_old、quantity_delta、effective_date、effective_at 必須全部相同
--      （比例可為 NULL，以 IS DISTINCT FROM 比較，NULL 對 NULL 視為相同）。
-- 「同一原列只能被沖銷一次」交給 uk_share_adjustments_reversal_of；「只能沖銷最晚一筆」屬應用層規則（04.2-08）。
-- 錯誤訊息一律靜態、不含任何欄位值。
CREATE OR REPLACE FUNCTION validate_share_adjustment_reversal()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    original share_adjustments%ROWTYPE;
BEGIN
    -- 一般紀錄（非沖銷）直接放行。
    IF NEW.reversal_of_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO original FROM share_adjustments WHERE id = NEW.reversal_of_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'share adjustment reversal rejected: original adjustment does not exist'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.user_id <> NEW.user_id THEN
        RAISE EXCEPTION 'share adjustment reversal rejected: original adjustment belongs to another user'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.asset_id <> NEW.asset_id THEN
        RAISE EXCEPTION 'share adjustment reversal rejected: asset does not match the original adjustment'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.reversal_of_id IS NOT NULL THEN
        RAISE EXCEPTION 'share adjustment reversal rejected: original adjustment is itself a reversal'
            USING ERRCODE = 'check_violation';
    END IF;
    IF original.type IS DISTINCT FROM NEW.type
        OR original.ratio_new IS DISTINCT FROM NEW.ratio_new
        OR original.ratio_old IS DISTINCT FROM NEW.ratio_old
        OR original.quantity_delta IS DISTINCT FROM NEW.quantity_delta
        OR original.effective_date IS DISTINCT FROM NEW.effective_date
        OR original.effective_at IS DISTINCT FROM NEW.effective_at THEN
        RAISE EXCEPTION 'share adjustment reversal rejected: values do not match the original adjustment'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_share_adjustments_validate_reversal
    BEFORE INSERT ON share_adjustments
    FOR EACH ROW EXECUTE FUNCTION validate_share_adjustment_reversal();
