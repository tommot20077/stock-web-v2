-- 使用者基準幣（Phase 04.1 / PORT-09、CONTEXT D-02）：summary 折算的單位由後端權威保存，
-- 前端的兩個入口（設定頁、儀表板切換）都寫回同一個欄位。
--
-- ── 為什麼可以直接 ADD COLUMN ... NOT NULL DEFAULT ─────────────────────────
-- 帶「常數」DEFAULT 的 ADD COLUMN 在 PostgreSQL 11 之後不會重寫整張表，只把預設值記在
-- catalog 裡（見 PostgreSQL 16 文件 ALTER TABLE 的 Notes）。既有使用者因此全部讀到 'TWD'，
-- 與 D-02 裁定的預設值一致，不需要另外回填，也不需要先加 nullable 欄位再補值。
--
-- ── 值域與 Java enum 的同步 ─────────────────────────────────────────────────
-- CHECK 的清單必須與 stock-common 的 CurrencyCode enum 一致（USD / TWD / EUR / JPY）。
-- 新增幣別時兩處要一起改：enum 加常數，並以新的 migration 重建本約束。應用層另有白名單
-- 驗證，這裡是第二道防線，擋住繞過 API 直接寫表的情況。
--
-- ── 命名提醒 ────────────────────────────────────────────────────────────────
-- users.base_currency 是「使用者的基準幣」；fx_rates.base_currency 是「幣對的基礎幣」。
-- 兩者同名異義，查詢時不要混用。
--
-- 版本號：V13 為目前最高版號（develop 與本分支一致）。同 wave 的 04.1-04 會建立 V15，
-- 本檔只擁有 V14。不使用 CONCURRENTLY；V1~V13 不得修改。
ALTER TABLE users ADD COLUMN base_currency VARCHAR(10) NOT NULL DEFAULT 'TWD';

ALTER TABLE users ADD CONSTRAINT ck_users_base_currency CHECK (base_currency IN ('USD', 'TWD', 'EUR', 'JPY'));
