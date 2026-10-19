-- V3__account.sql: Account table, account number sequence, constraints, and indexes

CREATE SEQUENCE IF NOT EXISTS core.account_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS core.account (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(32) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    balance NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    transaction_limit NUMERIC(19,2) NOT NULL,
    daily_limit NUMERIC(19,2) NOT NULL,
    opened_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES core.customer(id),
    CONSTRAINT chk_account_balance CHECK (balance >= 0),
    CONSTRAINT chk_account_limits CHECK (transaction_limit > 0 AND daily_limit >= transaction_limit)
);

CREATE INDEX IF NOT EXISTS idx_account_customer_id ON core.account (customer_id);
CREATE INDEX IF NOT EXISTS idx_account_status ON core.account (status);
CREATE INDEX IF NOT EXISTS idx_account_number ON core.account (account_number);
