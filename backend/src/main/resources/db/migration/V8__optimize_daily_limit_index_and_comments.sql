-- V8__optimize_daily_limit_index_and_comments.sql
-- Optimizes daily outgoing limit aggregation (sumOutgoingAmountSince) with a partial covering index
-- and standardizes table/column comments to English (R3-01, R3-07, R3-08).

CREATE INDEX idx_tx_source_completed_created_amount
    ON core.transaction (source_account_id, created_at DESC)
    INCLUDE (amount)
    WHERE status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT');

DROP INDEX IF EXISTS core.idx_customer_location;
CREATE INDEX idx_customer_location
    ON core.customer (COALESCE(NULLIF(TRIM(location), ''), 'UNKNOWN'))
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN core.transaction.system_balance_delta IS
    'Net change to total system deposits across all customer accounts (+amount for DEPOSIT, -(amount+fee) for WITHDRAWAL/PAYMENT, -fee for internal TRANSFER, and exact inverse for REVERSAL).';

COMMENT ON TABLE core.idempotency_key IS 'Stores idempotency keys to prevent duplicate execution and support safe retries';
COMMENT ON COLUMN core.idempotency_key.idem_key IS 'Client-supplied unique key from the Idempotency-Key request header';
COMMENT ON COLUMN core.idempotency_key.scope IS 'Isolation scope (authenticated username or DEFAULT)';
COMMENT ON COLUMN core.idempotency_key.request_hash IS 'SHA-256 hash of HTTP method, URI path, and canonical JSON request body';
COMMENT ON COLUMN core.idempotency_key.response_status IS 'HTTP status code returned to the client (e.g. 201)';
COMMENT ON COLUMN core.idempotency_key.response_body IS 'Serialized JSON response payload for deterministic replay';
COMMENT ON COLUMN core.idempotency_key.created_at IS 'Timestamp when the idempotency record was created';
COMMENT ON COLUMN core.idempotency_key.expires_at IS 'Expiration timestamp of the idempotency record (default 24h TTL)';
