-- V2__customer.sql: Customer table and customer code sequence

CREATE SEQUENCE IF NOT EXISTS core.customer_code_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS core.customer (
    id BIGSERIAL PRIMARY KEY,
    customer_code VARCHAR(32) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    national_id VARCHAR(32) NOT NULL UNIQUE,
    email VARCHAR(255),
    phone VARCHAR(32),
    address VARCHAR(255),
    location VARCHAR(100),
    date_of_birth DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

-- Partial index for location statistics and active customer lookup (L1-10)
CREATE INDEX IF NOT EXISTS idx_customer_location ON core.customer (location) WHERE deleted_at IS NULL;

-- Index to optimize soft-delete filtering
CREATE INDEX IF NOT EXISTS idx_customer_deleted_at ON core.customer (deleted_at);
