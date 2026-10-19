-- perf/seed-large.sql
-- Story 5.5: Large Seed Data for Performance & Index Benchmarking (NC-12, G-05, NFR-2)
-- Generates 10,000 synthetic customers, 10,000 accounts, and 1,000,000 transactions
-- using PostgreSQL 17 generate_series() in ~8-15 seconds on local PostgreSQL.
--
-- SAFETY GUARD: Refuses to run if connected to Supabase / production managed database.
-- Usage (local Docker or local PostgreSQL 17):
--   docker exec -i corebank-postgres psql -U corebank -d corebanking < perf/seed-large.sql

\timing on

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname IN ('supabase_admin', 'supabase_auth_admin', 'rds_superuser', 'neon_superuser')
           OR rolname LIKE 'render_%'
    ) THEN
        RAISE EXCEPTION 'SAFETY ABORT: perf/seed-large.sql must only be executed against a local benchmark database, never on Cloud DB (Supabase/Render/Neon/AWS).';
    END IF;
END $$;

BEGIN;

-- 1. Seed 10,000 synthetic customers (CUS9000001 .. CUS9010000)
INSERT INTO core.customer (
    customer_code,
    full_name,
    national_id,
    email,
    phone,
    address,
    location,
    date_of_birth,
    customer_type_id,
    customer_type_code,
    created_at
)
SELECT
    'CUS' || (9000000 + g)::text,
    'Khách Hàng Thử Tải ' || g::text,
    '0009' || LPAD(g::text, 8, '0'),
    'perf.user.' || g::text || '@example.com',
    '09' || LPAD((10000000 + g)::text, 8, '0'),
    'Số ' || (g % 500 + 1)::text || ' Đường Thử Tải, Khu vực ' || (g % 20 + 1)::text,
    CASE (g % 5)
        WHEN 0 THEN 'Hà Nội'
        WHEN 1 THEN 'Hồ Chí Minh'
        WHEN 2 THEN 'Đà Nẵng'
        WHEN 3 THEN 'Hải Phòng'
        ELSE 'Cần Thơ'
    END,
    DATE '1975-01-01' + ((g * 7) % 10000),
    CASE WHEN (g % 10) = 0
        THEN (SELECT id FROM core.customer_type WHERE code = 'BUSINESS')
        ELSE (SELECT id FROM core.customer_type WHERE code = 'INDIVIDUAL')
    END,
    CASE WHEN (g % 10) = 0 THEN 'BUSINESS' ELSE 'INDIVIDUAL' END,
    CURRENT_TIMESTAMP - ((g % 365) * INTERVAL '1 day')
FROM generate_series(1, 10000) AS g
ON CONFLICT (customer_code) DO NOTHING;

-- 2. Seed 10,000 synthetic accounts across LOW (<10M), MEDIUM (10M-<100M), and HIGH (>=100M) tiers
INSERT INTO core.account (
    account_number,
    customer_id,
    currency,
    balance,
    transaction_limit,
    daily_limit,
    opened_date,
    status,
    created_at
)
SELECT
    '1009' || LPAD(g::text, 7, '0') || ((g * 7) % 10)::text,
    c.id,
    'VND',
    CASE (g % 3)
        WHEN 0 THEN 5500000.00::numeric(19,2)
        WHEN 1 THEN 45000000.00::numeric(19,2)
        ELSE 250000000.00::numeric(19,2)
    END,
    CASE WHEN c.customer_type_code = 'BUSINESS'
        THEN 1000000000.00::numeric(19,2)
        ELSE 100000000.00::numeric(19,2)
    END,
    CASE WHEN c.customer_type_code = 'BUSINESS'
        THEN 5000000000.00::numeric(19,2)
        ELSE 500000000.00::numeric(19,2)
    END,
    CURRENT_DATE - (g % 365),
    'ACTIVE',
    CURRENT_TIMESTAMP - ((g % 365) * INTERVAL '1 day')
FROM generate_series(1, 10000) AS g
JOIN core.customer c ON c.customer_code = 'CUS' || (9000000 + g)::text
ON CONFLICT (account_number) DO NOTHING;

-- 3. Seed 1,000,000 synthetic transactions distributed across 365 days and 10,000 accounts
-- Temporarily disable autovacuum triggers during bulk insert for throughput
SET LOCAL synchronous_commit = off;

WITH account_bounds AS (
    SELECT MIN(id) AS min_id, COUNT(*)::int AS cnt
    FROM core.account
)
INSERT INTO core.transaction (
    reference_no,
    type,
    source_account_id,
    target_account_id,
    amount,
    fee,
    source_balance_after,
    target_balance_after,
    system_balance_delta,
    location,
    description,
    status,
    created_by,
    created_at
)
SELECT
    'TXN-PERF-' || LPAD(g::text, 7, '0'),
    CASE (g % 4)
        WHEN 0 THEN 'DEPOSIT'
        WHEN 1 THEN 'WITHDRAWAL'
        WHEN 2 THEN 'TRANSFER'
        ELSE 'PAYMENT'
    END,
    CASE WHEN (g % 4) = 0 THEN NULL ELSE (ab.min_id + (g % ab.cnt)) END,
    CASE WHEN (g % 4) IN (0, 2) THEN (ab.min_id + ((g + 13) % ab.cnt)) ELSE NULL END,
    ((g % 500) + 1) * 100000.00::numeric(19,2),
    CASE WHEN (g % 4) IN (1, 2) THEN 2000.00::numeric(19,2) ELSE 0.00::numeric(19,2) END,
    45000000.00::numeric(19,2),
    45000000.00::numeric(19,2),
    CASE (g % 4)
        WHEN 0 THEN ((g % 500) + 1) * 100000.00::numeric(19,2)
        WHEN 2 THEN -2000.00::numeric(19,2)
        ELSE -(((g % 500) + 1) * 100000.00::numeric(19,2))
    END,
    CASE (g % 5)
        WHEN 0 THEN 'Hà Nội'
        WHEN 1 THEN 'Hồ Chí Minh'
        WHEN 2 THEN 'Đà Nẵng'
        WHEN 3 THEN 'Hải Phòng'
        ELSE 'Cần Thơ'
    END,
    'Giao dịch thử tải tự động #' || g::text,
    'COMPLETED',
    'PERF_SEED',
    TIMESTAMPTZ '2026-10-19 00:00:00+07' - ((g * 31) * INTERVAL '1 second')
FROM generate_series(1, 1000000) AS g
CROSS JOIN account_bounds ab
ON CONFLICT (reference_no) DO NOTHING;

COMMIT;

-- Update planner statistics and visibility map for Index-Only Scans
VACUUM ANALYZE core.customer;
VACUUM ANALYZE core.account;
VACUUM ANALYZE core.transaction;
