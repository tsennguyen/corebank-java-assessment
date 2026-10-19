-- V4__transaction.sql: Transaction table, constraints, and query indexes

CREATE TABLE IF NOT EXISTS core.transaction (
    id BIGSERIAL PRIMARY KEY,
    reference_no VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL,
    source_account_id BIGINT,
    target_account_id BIGINT,
    amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    source_balance_after NUMERIC(19,2),
    target_balance_after NUMERIC(19,2),
    system_balance_delta NUMERIC(19,2) NOT NULL,
    location VARCHAR(255),
    description VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    reversal_of_id BIGINT UNIQUE,
    recurring_id BIGINT,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_tx_source_account FOREIGN KEY (source_account_id) REFERENCES core.account(id),
    CONSTRAINT fk_tx_target_account FOREIGN KEY (target_account_id) REFERENCES core.account(id),
    CONSTRAINT fk_tx_reversal_of FOREIGN KEY (reversal_of_id) REFERENCES core.transaction(id),
    CONSTRAINT chk_tx_amount CHECK (amount > 0),
    CONSTRAINT chk_tx_fee CHECK (fee >= 0)
);

CREATE INDEX IF NOT EXISTS idx_tx_source_created ON core.transaction (source_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_target_created ON core.transaction (target_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_created ON core.transaction (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_type_created ON core.transaction (type, created_at DESC);
