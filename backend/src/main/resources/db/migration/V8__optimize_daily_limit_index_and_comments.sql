-- V8__optimize_daily_limit_index_and_comments.sql
-- Optimizes daily outgoing limit aggregation (sumOutgoingAmountSince) with a partial covering index
-- and documents the ledger invariant column core.transaction.system_balance_delta (R3-01, R3-07).

CREATE INDEX idx_tx_source_completed_created_amount
    ON core.transaction (source_account_id, created_at DESC)
    INCLUDE (amount)
    WHERE status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT');

COMMENT ON COLUMN core.transaction.system_balance_delta IS
    'Net change to total system deposits across all customer accounts (+amount for DEPOSIT, -(amount+fee) for WITHDRAWAL/PAYMENT, -fee for internal TRANSFER, and exact inverse for REVERSAL).';
