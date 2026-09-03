CREATE OR REPLACE FUNCTION check_entries_zero_sum() RETURNS TRIGGER AS $$
DECLARE
    net BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
    INTO net
    FROM entries
    WHERE transaction_id = NEW.transaction_id;

    IF net <> 0 THEN
        RAISE EXCEPTION 'entries for transaction % do not sum to zero (net = %)', NEW.transaction_id, net;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_zero_sum
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entries_zero_sum();
