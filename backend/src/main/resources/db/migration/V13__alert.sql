-- V13__alert.sql: Suspicious transaction alerts for real-time anomaly detection and historical fraud scans (L3-03, L3-04, FR-22, FR-23, ADR-012)

CREATE TABLE IF NOT EXISTS core.alert (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES core.transaction(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES core.account(id) ON DELETE CASCADE,
    rule_code VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    resolution_note VARCHAR(500),
    handled_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_alert_rule_code CHECK (rule_code IN ('AMOUNT_THRESHOLD', 'VELOCITY')),
    CONSTRAINT chk_alert_severity CHECK (severity IN ('MEDIUM', 'HIGH')),
    CONSTRAINT chk_alert_status CHECK (status IN ('NEW', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE')),
    CONSTRAINT uk_alert_tx_rule UNIQUE (transaction_id, rule_code)
);

CREATE INDEX IF NOT EXISTS idx_alert_status_created
    ON core.alert (status, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_alert_account_created
    ON core.alert (account_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_alert_rule_created
    ON core.alert (rule_code, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_alert_created
    ON core.alert (created_at DESC, id DESC);
