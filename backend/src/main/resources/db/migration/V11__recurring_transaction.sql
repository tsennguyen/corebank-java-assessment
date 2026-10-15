-- V11__recurring_transaction.sql: Recurring transaction setup table, constraints, and partial due index (L2-06, L2-07, FR-16, FR-17)

CREATE TABLE IF NOT EXISTS core.recurring_transaction (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    source_account_id BIGINT,
    target_account_id BIGINT,
    amount NUMERIC(19, 2) NOT NULL,
    location VARCHAR(255),
    description VARCHAR(500),
    frequency VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failure_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_rec_source_account FOREIGN KEY (source_account_id) REFERENCES core.account (id),
    CONSTRAINT fk_rec_target_account FOREIGN KEY (target_account_id) REFERENCES core.account (id),
    CONSTRAINT chk_rec_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_rec_type CHECK (type IN ('DEPOSIT', 'PAYMENT', 'TRANSFER')),
    CONSTRAINT chk_rec_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT chk_rec_status CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT chk_rec_failure_count CHECK (failure_count >= 0),
    CONSTRAINT chk_rec_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT chk_rec_accounts_by_type CHECK (
        (type = 'DEPOSIT' AND source_account_id IS NULL AND target_account_id IS NOT NULL)
        OR (type = 'PAYMENT' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
        OR (type = 'TRANSFER' AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL AND source_account_id <> target_account_id)
    )
);

CREATE INDEX IF NOT EXISTS idx_rec_due
    ON core.recurring_transaction (next_run_at ASC, id ASC)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_rec_source_account
    ON core.recurring_transaction (source_account_id, status)
    WHERE source_account_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rec_target_account
    ON core.recurring_transaction (target_account_id, status)
    WHERE target_account_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rec_status_created
    ON core.recurring_transaction (status, created_at DESC, id DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_tx_recurring'
    ) THEN
        ALTER TABLE core.transaction
            ADD CONSTRAINT fk_tx_recurring
            FOREIGN KEY (recurring_id) REFERENCES core.recurring_transaction (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_tx_recurring_id
    ON core.transaction (recurring_id)
    WHERE recurring_id IS NOT NULL;

COMMENT ON TABLE core.recurring_transaction IS 'Scheduled recurring deposit, payment, and transfer instructions (L2-06, L2-07)';
COMMENT ON INDEX core.idx_rec_due IS 'Partial index for scheduler polling of due ACTIVE recurring transactions (next_run_at ASC, id ASC)';
