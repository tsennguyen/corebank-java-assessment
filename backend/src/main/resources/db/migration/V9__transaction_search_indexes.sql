-- V9__transaction_search_indexes.sql: Additional composite indexes for advanced multi-filter transaction search (L2-03, L2-04, G-05)

CREATE INDEX IF NOT EXISTS idx_tx_status_created
    ON core.transaction (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tx_amount_created
    ON core.transaction (amount DESC, created_at DESC);
