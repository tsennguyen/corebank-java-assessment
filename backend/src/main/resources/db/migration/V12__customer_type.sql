-- V12__customer_type.sql: Customer classification (INDIVIDUAL / BUSINESS) and maximum limits per customer type (L3-05, L3-06, FR-18)

CREATE TABLE IF NOT EXISTS core.customer_type (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    max_transaction_limit NUMERIC(19, 2) NOT NULL CHECK (max_transaction_limit > 0),
    max_daily_limit NUMERIC(19, 2) NOT NULL CHECK (max_daily_limit >= max_transaction_limit),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO core.customer_type (id, code, name, max_transaction_limit, max_daily_limit, created_at, updated_at, version)
VALUES
    (1, 'INDIVIDUAL', 'Cá nhân', 100000000.00, 500000000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    (2, 'BUSINESS', 'Doanh nghiệp', 2000000000.00, 10000000000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (code) DO NOTHING;

SELECT setval(pg_get_serial_sequence('core.customer_type', 'id'), (SELECT COALESCE(MAX(id), 1) FROM core.customer_type), true);

ALTER TABLE core.customer
    ADD COLUMN IF NOT EXISTS customer_type_id BIGINT NOT NULL DEFAULT 1 REFERENCES core.customer_type(id),
    ADD COLUMN IF NOT EXISTS customer_type_code VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL' REFERENCES core.customer_type(code);

CREATE INDEX IF NOT EXISTS idx_customer_type_id ON core.customer (customer_type_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_customer_type_code ON core.customer (customer_type_code) WHERE deleted_at IS NULL;
