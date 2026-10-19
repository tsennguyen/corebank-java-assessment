-- V14__scale_and_hardening.sql: Keyset pagination indexes (6.1), ShedLock table (6.2), and append-only audit_log table (6.3)

-- 1. Keyset pagination composite indexes on core.transaction (Story 6.1, NC-13, G-05)
CREATE INDEX IF NOT EXISTS idx_tx_src_keyset
    ON core.transaction (source_account_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_tx_tgt_keyset
    ON core.transaction (target_account_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_tx_created_id_keyset
    ON core.transaction (created_at DESC, id DESC);

-- 2. Distributed scheduler lock table for multi-instance cron jobs (Story 6.2, NC-13, L2-07)
CREATE TABLE IF NOT EXISTS core.shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

-- 3. Immutable append-only audit_log table for sensitive administrative operations (Story 6.3, NC-13, L2-01)
CREATE TABLE IF NOT EXISTS core.audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    before_json TEXT,
    after_json TEXT,
    request_id VARCHAR(64),
    ip VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created
    ON core.audit_log (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_created
    ON core.audit_log (actor, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_action_created
    ON core.audit_log (action, created_at DESC, id DESC);

CREATE OR REPLACE FUNCTION core.prevent_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'core.audit_log is append-only; % operation is prohibited', TG_OP
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_log_append_only ON core.audit_log;
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON core.audit_log
    FOR EACH ROW
    EXECUTE FUNCTION core.prevent_audit_log_mutation();
