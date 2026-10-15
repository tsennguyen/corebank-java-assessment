-- V10__account_status_history.sql: Account status lifecycle audit trail table, constraints, index, and backfill (L2-05, FR-3)

CREATE TABLE IF NOT EXISTS core.account_status_history (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_acc_status_hist_account FOREIGN KEY (account_id) REFERENCES core.account(id),
    CONSTRAINT chk_acc_status_hist_from CHECK (from_status IS NULL OR from_status IN ('ACTIVE', 'LOCKED', 'CLOSED')),
    CONSTRAINT chk_acc_status_hist_to CHECK (to_status IN ('ACTIVE', 'LOCKED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_acc_status_hist_account_changed
    ON core.account_status_history (account_id, changed_at DESC, id DESC);

-- Backfill initial status history record for any pre-existing accounts that do not yet have history
INSERT INTO core.account_status_history (account_id, from_status, to_status, reason, changed_by, changed_at)
SELECT a.id, NULL, a.status, 'Account opened', 'SYSTEM', a.created_at
FROM core.account a
WHERE NOT EXISTS (
    SELECT 1 FROM core.account_status_history h WHERE h.account_id = a.id
);

COMMENT ON TABLE core.account_status_history IS 'Immutable audit trail of account status transitions (ACTIVE, LOCKED, CLOSED)';
COMMENT ON INDEX core.idx_acc_status_hist_account_changed IS 'Supports ordered lookup of status history for a specific account (changed_at DESC, id DESC)';
