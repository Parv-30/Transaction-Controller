ALTER TABLE transactions ADD COLUMN reversal_of_transaction_id UUID REFERENCES transactions(id);
CREATE UNIQUE INDEX uq_transactions_reversal_of_transaction_id
    ON transactions (reversal_of_transaction_id)
    WHERE reversal_of_transaction_id IS NOT NULL;
