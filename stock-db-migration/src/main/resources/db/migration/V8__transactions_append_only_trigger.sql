-- transactions 表為 append-only 帳本（architecture.md §Compliance、security.md §11）。
-- 於資料庫層以 trigger 強制：僅允許 INSERT，禁止 UPDATE / DELETE / TRUNCATE。
-- 匿名化流程（§11）只改 users 表的 username/email，transactions.user_id 外鍵不變，
-- 故此處全面禁止異動不與匿名化設計衝突。

CREATE OR REPLACE FUNCTION prevent_transactions_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'transactions is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_transactions_no_update
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_transactions_mutation();

CREATE TRIGGER trg_transactions_no_delete
    BEFORE DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_transactions_mutation();

CREATE TRIGGER trg_transactions_no_truncate
    BEFORE TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_transactions_mutation();
