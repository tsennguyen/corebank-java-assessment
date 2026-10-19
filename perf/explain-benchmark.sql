-- EXPLAIN (ANALYZE, BUFFERS) benchmark on the perf/seed-large.sql dataset (~1,000,000 transactions).
-- Usage: psql -d <db> -f perf/explain-benchmark.sql   (run twice, read the second, warm run)
-- "WITHOUT index" runs force a sequential scan with enable_*scan = off instead of dropping indexes.
\pset pager off

-- A typical account (seed-large spreads 1M rows over 10,000 accounts) and the busiest source account.
SELECT 5000 AS typ \gset
SELECT a AS hot FROM (SELECT source_account_id a, count(*) c FROM core.transaction
  WHERE source_account_id IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 1) x \gset
SELECT count(*) AS typ_rows FROM core.transaction WHERE source_account_id = :typ OR target_account_id = :typ;

\echo '=== Q1 account history (30 days, LIMIT 20) WITHOUT index'
SET enable_indexscan = off; SET enable_bitmapscan = off; SET enable_indexonlyscan = off;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id, created_at FROM core.transaction
 WHERE (source_account_id = :typ OR target_account_id = :typ) AND created_at >= now() - interval '30 days'
 ORDER BY created_at DESC, id DESC LIMIT 20;
RESET enable_indexscan; RESET enable_bitmapscan; RESET enable_indexonlyscan;

\echo '=== Q1 account history WITH indexes'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id, created_at FROM core.transaction
 WHERE (source_account_id = :typ OR target_account_id = :typ) AND created_at >= now() - interval '30 days'
 ORDER BY created_at DESC, id DESC LIMIT 20;

\echo '=== Q2 daily outgoing sum (sumOutgoingAmountSince) WITHOUT index'
SET enable_indexscan = off; SET enable_bitmapscan = off; SET enable_indexonlyscan = off;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT COALESCE(SUM(amount), 0) FROM core.transaction
 WHERE source_account_id = :hot AND status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
   AND created_at >= date_trunc('day', now());
RESET enable_indexscan; RESET enable_bitmapscan; RESET enable_indexonlyscan;

\echo '=== Q2 daily outgoing sum WITH indexes'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT COALESCE(SUM(amount), 0) FROM core.transaction
 WHERE source_account_id = :hot AND status = 'COMPLETED' AND type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
   AND created_at >= date_trunc('day', now());

-- Deep pagination over the whole ledger (index idx_tx_created_id_keyset, V14).
SELECT created_at AS cc, id AS ci FROM core.transaction ORDER BY created_at DESC, id DESC OFFSET 499999 LIMIT 1 \gset

\echo '=== Q3 page at row 500,000 with OFFSET'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id, created_at FROM core.transaction
 ORDER BY created_at DESC, id DESC OFFSET 500000 LIMIT 20;

\echo '=== Q3 same page with keyset cursor (created_at, id)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id, created_at FROM core.transaction
 WHERE (created_at, id) < (:'cc', :ci) ORDER BY created_at DESC, id DESC LIMIT 20;

\echo '=== Q3b page at row 100,000 with OFFSET'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id, created_at FROM core.transaction
 ORDER BY created_at DESC, id DESC OFFSET 100000 LIMIT 20;
