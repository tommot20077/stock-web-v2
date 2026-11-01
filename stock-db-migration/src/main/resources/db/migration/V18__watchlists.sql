-- 觀察清單（Phase 04.2，WATCH-01 / WATCH-02；決策 D-14 ~ D-19）。
--
-- ── 為什麼不是 append-only ──────────────────────────────────────────────────
-- 觀察清單是可變的使用者偏好（UI 上的星號與排序），不是需要稽核的資金歷史：改名、刪除、移除項目、
-- 重排都直接改寫列，不設 append-only trigger，也不需要沖銷或冪等鍵（加入 / 移除 / 改名 / 重排天然冪等，
-- 建立清單由 uk_watchlists_user_name 擋雙擊）。
--
-- ── 為什麼路徑用 uuid ────────────────────────────────────────────────────────
-- API 一律以清單 uuid 與資產 uuid 定位，不放 symbol：FX 代號含「/」（USD/TWD、EUR/USD、USD/JPY），
-- 放進路徑會被 servlet 容器拒絕或誤切。內部 BIGSERIAL id 不對外，避免可列舉。
--
-- ── 為什麼 position 是整數 + 整份重排 ────────────────────────────────────────
-- 每份清單最多 100 檔（應用層上限），重排時一次交易內依前端送來的順序重寫 1..n，成本可忽略；
-- 不用分數索引 / LexoRank。整份重寫也讓「送來的集合 ≠ 目前集合」可直接偵測為並發衝突（409）。
-- position 不設唯一約束：整份重寫的中途狀態會短暫重複，唯一約束會讓逐列 UPDATE 失敗。
--
-- ── 版本號說明 ──────────────────────────────────────────────────────────────
-- 同 wave 的 04.2-03 擁有 V16、04.2-07 擁有 V17；三者各自新增不同版號的新檔、不共用任何檔案。

CREATE TABLE watchlists (
    id         BIGSERIAL    PRIMARY KEY,
    uuid       UUID         NOT NULL DEFAULT uuid_generate_v4(),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    -- 應用層 trim 後 1–30 字（PostgreSQL VARCHAR 以字元計，與應用層以 code point 計一致）。
    name       VARCHAR(30)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_watchlists_uuid UNIQUE (uuid),
    -- 同使用者清單名稱唯一（D-17）：重名 409 的依據，也是預設清單「我的自選」
    -- 以 ON CONFLICT (user_id, name) DO NOTHING 併發建立時只留一列的依據。
    CONSTRAINT uk_watchlists_user_name UNIQUE (user_id, name),
    -- trim 後不得為空：應用層已擋，DB 層兜底（name 為 NOT NULL，CHECK 不會因 NULL 而被放行）。
    CONSTRAINT ck_watchlists_name_not_blank CHECK (btrim(name) <> '')
);

-- 清單之間依 created_at, id 排序；所有查詢都以 user_id 篩選。
-- 刻意採一般（阻塞式）建索引：理由見 V11；本表剛建立，沒有資料可鎖。
CREATE INDEX idx_watchlists_user ON watchlists (user_id, created_at, id);

CREATE TABLE watchlist_items (
    -- 刪除清單時項目一併刪除（DELETE /api/v1/watchlists/{id}）。
    watchlist_id BIGINT      NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    asset_id     BIGINT      NOT NULL REFERENCES assets(id),
    position     INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- 同一清單內同一資產只出現一次；同一資產可同時在多份清單。
    CONSTRAINT pk_watchlist_items PRIMARY KEY (watchlist_id, asset_id),
    CONSTRAINT ck_watchlist_items_position_positive CHECK (position > 0)
);

-- 清單內依 position 排序讀取。
CREATE INDEX idx_watchlist_items_order ON watchlist_items (watchlist_id, position);
