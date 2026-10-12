-- V5__drop_redundant_indexes.sql: account_number is UNIQUE, which already creates a B-tree index.
-- The extra idx_account_number from V3 doubles write cost and storage without serving any query.
DROP INDEX IF EXISTS core.idx_account_number;
