package com.example.corebanking.fraud.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository executing set-based SQL anomaly scans over historical outgoing transactions using
 * PostgreSQL window functions ({@code COUNT(*) OVER (PARTITION BY source_account_id ORDER BY
 * created_at RANGE BETWEEN make_interval(secs => :windowSeconds) PRECEDING AND CURRENT ROW)}) and
 * idempotent {@code INSERT ... ON CONFLICT (transaction_id, rule_code) DO NOTHING} (L3-03, L3-04,
 * G-05, FR-22, ADR-012).
 */
@Repository
public class FraudScanRepository {

    static final String SCAN_AMOUNT_THRESHOLD_SQL =
            """
INSERT INTO core.alert (
    transaction_id, account_id, rule_code, severity, reason, status, created_at, updated_at, version
)
SELECT
    t.id,
    t.source_account_id,
    'AMOUNT_THRESHOLD',
    'HIGH',
    'Outgoing ' || t.type || ' amount ' || t.amount::text
        || ' meets or exceeds anomaly threshold ' || CAST(:threshold AS NUMERIC(19, 2))::text,
    'NEW',
    t.created_at,
    :now,
    0
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
  AND t.source_account_id IS NOT NULL
  AND t.created_at >= :fromInstant
  AND t.created_at <= :toInstant
  AND t.amount >= :threshold
ORDER BY t.created_at ASC, t.id ASC
ON CONFLICT (transaction_id, rule_code) DO NOTHING
""";

    static final String SCAN_VELOCITY_WINDOW_SQL =
            """
WITH windowed_outgoing AS (
    SELECT
        t.id AS transaction_id,
        t.source_account_id AS account_id,
        t.created_at,
        COUNT(*) OVER (
            PARTITION BY t.source_account_id
            ORDER BY t.created_at
            RANGE BETWEEN make_interval(secs => :windowSeconds) PRECEDING AND CURRENT ROW
        ) AS tx_count_in_window
    FROM core.transaction t
    WHERE t.status = 'COMPLETED'
      AND t.type IN ('WITHDRAWAL', 'TRANSFER', 'PAYMENT')
      AND t.source_account_id IS NOT NULL
      AND t.created_at >= :lookbackFromInstant
      AND t.created_at <= :toInstant
)
INSERT INTO core.alert (
    transaction_id, account_id, rule_code, severity, reason, status, created_at, updated_at, version
)
SELECT
    w.transaction_id,
    w.account_id,
    'VELOCITY',
    'MEDIUM',
    'Account ' || w.account_id || ' executed ' || w.tx_count_in_window
        || ' outgoing transactions within ' || :windowSeconds
        || ' seconds (threshold > ' || :maxCount || ')',
    'NEW',
    w.created_at,
    :now,
    0
FROM windowed_outgoing w
WHERE w.created_at >= :fromInstant
  AND w.tx_count_in_window > :maxCount
ORDER BY w.created_at ASC, w.transaction_id ASC
ON CONFLICT (transaction_id, rule_code) DO NOTHING
""";

    private final JdbcClient jdbcClient;

    public FraudScanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Scans completed outgoing transactions in {@code [fromInstant, toInstant]} whose {@code amount
     * >= threshold} and inserts missing {@code AMOUNT_THRESHOLD} alerts idempotently.
     *
     * @param fromInstant inclusive start instant
     * @param toInstant inclusive end instant
     * @param threshold minimum amount triggering the rule
     * @param now timestamp assigned to newly created alerts
     * @return number of newly inserted {@code AMOUNT_THRESHOLD} alert rows
     */
    public int scanAmountThresholdAnomalies(
            Instant fromInstant, Instant toInstant, BigDecimal threshold, Instant now) {
        OffsetDateTime fromUtc = OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
        OffsetDateTime toUtc = OffsetDateTime.ofInstant(toInstant, ZoneOffset.UTC);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        return jdbcClient
                .sql(SCAN_AMOUNT_THRESHOLD_SQL)
                .param("fromInstant", fromUtc)
                .param("toInstant", toUtc)
                .param("threshold", threshold)
                .param("now", nowUtc)
                .update();
    }

    /**
     * Scans completed outgoing transactions in {@code [fromInstant, toInstant]} (with a lookback of
     * {@code windowSeconds} before {@code fromInstant} so boundary bursts are not missed) using a
     * PostgreSQL {@code RANGE} window function and inserts missing {@code VELOCITY} alerts
     * idempotently.
     *
     * @param fromInstant inclusive start instant
     * @param toInstant inclusive end instant
     * @param windowSeconds sliding window size in seconds (e.g. 60)
     * @param maxCount maximum permitted outgoing transactions within the window (e.g. 5)
     * @param now timestamp assigned to newly created alerts
     * @return number of newly inserted {@code VELOCITY} alert rows
     */
    public int scanVelocityAnomalies(
            Instant fromInstant, Instant toInstant, long windowSeconds, int maxCount, Instant now) {
        long safeWindowSeconds = Math.max(1L, windowSeconds);
        Instant lookbackFrom = fromInstant.minusSeconds(safeWindowSeconds);
        OffsetDateTime lookbackFromUtc = OffsetDateTime.ofInstant(lookbackFrom, ZoneOffset.UTC);
        OffsetDateTime fromUtc = OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
        OffsetDateTime toUtc = OffsetDateTime.ofInstant(toInstant, ZoneOffset.UTC);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        return jdbcClient
                .sql(SCAN_VELOCITY_WINDOW_SQL)
                .param("lookbackFromInstant", lookbackFromUtc)
                .param("fromInstant", fromUtc)
                .param("toInstant", toUtc)
                .param("windowSeconds", (double) safeWindowSeconds)
                .param("maxCount", maxCount)
                .param("now", nowUtc)
                .update();
    }
}
