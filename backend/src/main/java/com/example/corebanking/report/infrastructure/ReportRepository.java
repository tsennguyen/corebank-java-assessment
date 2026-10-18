package com.example.corebanking.report.infrastructure;

import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only reporting repository executing periodic transaction aggregations and time-series trend
 * reconstructions directly in PostgreSQL via {@link JdbcClient} without loading transaction rows
 * into JVM memory (L3-01, L3-02, L3-08, G-05, FR-19, FR-20, ADR-013).
 */
@Repository
@RequiredArgsConstructor
public class ReportRepository {

    private static final String PERIODIC_TRANSACTION_REPORT_SQL =
            """
SELECT (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS period_start,
       COUNT(*) AS tx_count,
       COALESCE(SUM(t.amount), 0.00) AS total_amount,
       COALESCE(ROUND(AVG(t.amount), 2), 0.00) AS average_amount,
       COALESCE(MAX(t.amount), 0.00) AS max_amount,
       COALESCE(MIN(t.amount), 0.00) AS min_amount,
       COALESCE(SUM(t.fee), 0.00) AS total_fee
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type <> 'REVERSAL'
  AND t.created_at >= :fromInstant
  AND t.created_at < :toExclusiveInstant
  AND (CAST(:type AS VARCHAR) IS NULL OR t.type = CAST(:type AS VARCHAR))
GROUP BY 1
ORDER BY 1 ASC
""";

    private static final String SYSTEM_TREND_SERIES_SQL =
            """
WITH buckets AS (
    SELECT gs::date AS bucket
    FROM generate_series(
        date_trunc(CAST(:unit AS TEXT), CAST(:fromDate AS timestamp)),
        date_trunc(CAST(:unit AS TEXT), CAST(:toDate AS timestamp)),
        CAST(:step AS interval)
    ) AS gs
),
tx_by_bucket AS (
    SELECT (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS bucket,
           COUNT(*) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL') AS tx_count,
           COALESCE(SUM(t.amount) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_amount,
           COALESCE(SUM(t.fee) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_fee,
           COALESCE(SUM(t.system_balance_delta), 0.00) AS bucket_delta
    FROM core.transaction t
    WHERE t.created_at >= :fromInstant
      AND t.created_at < :toExclusiveInstant
    GROUP BY 1
),
post_range_delta AS (
    SELECT COALESCE(SUM(t.system_balance_delta), 0.00) AS after_to_delta
    FROM core.transaction t
    WHERE t.created_at >= :toExclusiveInstant
),
current_system_balance AS (
    SELECT COALESCE(SUM(a.balance), 0.00) AS total_balance
    FROM core.account a
)
SELECT b.bucket AS bucket,
       COALESCE(tb.tx_count, 0) AS tx_count,
       COALESCE(tb.total_amount, 0.00) AS total_amount,
       COALESCE(tb.total_fee, 0.00) AS total_fee,
       (csb.total_balance
        - prd.after_to_delta
        - COALESCE(SUM(COALESCE(tb.bucket_delta, 0.00)) OVER (
            ORDER BY b.bucket ASC
            ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING
          ), 0.00)
       ) AS closing_balance
FROM buckets b
LEFT JOIN tx_by_bucket tb ON tb.bucket = b.bucket
CROSS JOIN current_system_balance csb
CROSS JOIN post_range_delta prd
ORDER BY b.bucket ASC
""";

    private static final String ACCOUNT_TREND_SERIES_SQL =
            """
WITH buckets AS (
    SELECT gs::date AS bucket
    FROM generate_series(
        date_trunc(CAST(:unit AS TEXT), CAST(:fromDate AS timestamp)),
        date_trunc(CAST(:unit AS TEXT), CAST(:toDate AS timestamp)),
        CAST(:step AS interval)
    ) AS gs
),
tx_by_bucket AS (
    SELECT (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS bucket,
           COUNT(*) AS tx_count,
           COALESCE(SUM(t.amount), 0.00) AS total_amount,
           COALESCE(SUM(t.fee), 0.00) AS total_fee
    FROM core.transaction t
    WHERE (t.source_account_id = :accountId OR t.target_account_id = :accountId)
      AND t.status = 'COMPLETED'
      AND t.type <> 'REVERSAL'
      AND t.created_at >= :fromInstant
      AND t.created_at < :toExclusiveInstant
    GROUP BY 1
),
last_tx_per_bucket AS (
    SELECT DISTINCT ON (bucket)
           (date_trunc(CAST(:unit AS TEXT), t.created_at AT TIME ZONE CAST(:zone AS TEXT)))::date AS bucket,
           CASE
               WHEN t.source_account_id = :accountId THEN t.source_balance_after
               ELSE t.target_balance_after
           END AS last_balance_after
    FROM core.transaction t
    WHERE (t.source_account_id = :accountId OR t.target_account_id = :accountId)
      AND t.created_at >= :fromInstant
      AND t.created_at < :toExclusiveInstant
    ORDER BY bucket ASC, t.created_at DESC, t.id DESC
),
prior_tx AS (
    SELECT CASE
               WHEN t.source_account_id = :accountId THEN t.source_balance_after
               ELSE t.target_balance_after
           END AS balance_after
    FROM core.transaction t
    WHERE (t.source_account_id = :accountId OR t.target_account_id = :accountId)
      AND t.created_at < :fromInstant
    ORDER BY t.created_at DESC, t.id DESC
    LIMIT 1
),
first_tx_after AS (
    SELECT CASE
               WHEN t.source_account_id = :accountId THEN t.source_balance_after + t.amount + t.fee
               ELSE t.target_balance_after - t.amount
           END AS balance_before
    FROM core.transaction t
    WHERE (t.source_account_id = :accountId OR t.target_account_id = :accountId)
      AND t.created_at >= :fromInstant
    ORDER BY t.created_at ASC, t.id ASC
    LIMIT 1
),
initial_bal AS (
    SELECT COALESCE(
        (SELECT balance_after FROM prior_tx),
        (SELECT balance_before FROM first_tx_after),
        (SELECT a.balance FROM core.account a WHERE a.id = :accountId),
        0.00
    ) AS start_balance
)
SELECT b.bucket AS bucket,
       COALESCE(tb.tx_count, 0) AS tx_count,
       COALESCE(tb.total_amount, 0.00) AS total_amount,
       COALESCE(tb.total_fee, 0.00) AS total_fee,
       ltb.last_balance_after AS bucket_closing_balance,
       ib.start_balance AS initial_balance
FROM buckets b
LEFT JOIN tx_by_bucket tb ON tb.bucket = b.bucket
LEFT JOIN last_tx_per_bucket ltb ON ltb.bucket = b.bucket
CROSS JOIN initial_bal ib
ORDER BY b.bucket ASC
""";

    private final JdbcClient jdbcClient;

    /**
     * Aggregates completed business transactions (excluding {@code REVERSED} and {@code REVERSAL})
     * by {@link ReportPeriod} bucket in the specified business timezone.
     *
     * @param period aggregation period ({@code WEEK}, {@code QUARTER}, or {@code YEAR})
     * @param fromInstant inclusive lower timestamp bound
     * @param toExclusiveInstant exclusive upper timestamp bound
     * @param type optional transaction type filter
     * @param zoneId business timezone used for {@code date_trunc} bucketing
     * @return chronological list of aggregated period buckets
     */
    public List<TransactionReportBucketResponse> findPeriodicTransactionReport(
            ReportPeriod period,
            Instant fromInstant,
            Instant toExclusiveInstant,
            TransactionType type,
            ZoneId zoneId) {
        return jdbcClient
                .sql(PERIODIC_TRANSACTION_REPORT_SQL)
                .param("unit", period.sqlUnit(), Types.VARCHAR)
                .param("zone", zoneId.getId(), Types.VARCHAR)
                .param("fromInstant", OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC))
                .param(
                        "toExclusiveInstant",
                        OffsetDateTime.ofInstant(toExclusiveInstant, ZoneOffset.UTC))
                .param("type", type != null ? type.name() : null, Types.VARCHAR)
                .query(
                        (rs, rowNum) ->
                                new TransactionReportBucketResponse(
                                        rs.getObject("period_start", LocalDate.class),
                                        rs.getLong("tx_count"),
                                        scaleMoney(rs.getBigDecimal("total_amount")),
                                        scaleMoney(rs.getBigDecimal("average_amount")),
                                        scaleMoney(rs.getBigDecimal("max_amount")),
                                        scaleMoney(rs.getBigDecimal("min_amount")),
                                        scaleMoney(rs.getBigDecimal("total_fee"))))
                .list();
    }

    /**
     * Checks whether an account with the given ID exists in {@code core.account}.
     *
     * @param accountId account primary key
     * @return {@code true} if the account exists
     */
    public boolean existsAccountById(Long accountId) {
        Boolean exists =
                jdbcClient
                        .sql("SELECT EXISTS(SELECT 1 FROM core.account WHERE id = :accountId)")
                        .param("accountId", accountId)
                        .query(Boolean.class)
                        .single();
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Computes system-wide transaction volume, fee revenue, and closing balance per bucket in a
     * single SQL query using {@code generate_series} and a reverse cumulative window function over
     * {@code system_balance_delta} (AC 1, L3-08, FR-20, ADR-013).
     *
     * @param granularity bucket granularity ({@code DAY}, {@code WEEK}, {@code MONTH})
     * @param fromDate inclusive start date in business timezone
     * @param toDate inclusive end date in business timezone
     * @param fromInstant inclusive lower timestamp bound
     * @param toExclusiveInstant exclusive upper timestamp bound
     * @param zoneId business timezone
     * @return chronological list of trend points across all buckets in {@code [fromDate, toDate]}
     */
    public List<TrendPointResponse> findSystemTrendSeries(
            TrendGranularity granularity,
            LocalDate fromDate,
            LocalDate toDate,
            Instant fromInstant,
            Instant toExclusiveInstant,
            ZoneId zoneId) {
        return jdbcClient
                .sql(SYSTEM_TREND_SERIES_SQL)
                .param("unit", granularity.sqlUnit(), Types.VARCHAR)
                .param("step", granularity.sqlStep(), Types.VARCHAR)
                .param("fromDate", fromDate.toString(), Types.VARCHAR)
                .param("toDate", toDate.toString(), Types.VARCHAR)
                .param("zone", zoneId.getId(), Types.VARCHAR)
                .param("fromInstant", OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC))
                .param(
                        "toExclusiveInstant",
                        OffsetDateTime.ofInstant(toExclusiveInstant, ZoneOffset.UTC))
                .query(
                        (rs, rowNum) ->
                                new TrendPointResponse(
                                        rs.getObject("bucket", LocalDate.class),
                                        rs.getLong("tx_count"),
                                        scaleMoney(rs.getBigDecimal("total_amount")),
                                        scaleMoney(rs.getBigDecimal("total_fee")),
                                        scaleMoney(rs.getBigDecimal("closing_balance"))))
                .list();
    }

    /**
     * Computes single-account transaction volume, fee, and closing balance per bucket using {@code
     * DISTINCT ON (bucket)} for the last {@code balance_after} in each bucket and carrying forward
     * the previous closing balance across sparse buckets without transactions (AC 2, L3-08, FR-20).
     *
     * @param granularity bucket granularity ({@code DAY}, {@code WEEK}, {@code MONTH})
     * @param accountId target account primary key
     * @param fromDate inclusive start date in business timezone
     * @param toDate inclusive end date in business timezone
     * @param fromInstant inclusive lower timestamp bound
     * @param toExclusiveInstant exclusive upper timestamp bound
     * @param zoneId business timezone
     * @return chronological list of trend points for the account
     */
    public List<TrendPointResponse> findAccountTrendSeries(
            TrendGranularity granularity,
            Long accountId,
            LocalDate fromDate,
            LocalDate toDate,
            Instant fromInstant,
            Instant toExclusiveInstant,
            ZoneId zoneId) {
        List<AccountTrendRawRow> rawRows =
                jdbcClient
                        .sql(ACCOUNT_TREND_SERIES_SQL)
                        .param("unit", granularity.sqlUnit(), Types.VARCHAR)
                        .param("step", granularity.sqlStep(), Types.VARCHAR)
                        .param("accountId", accountId)
                        .param("fromDate", fromDate.toString(), Types.VARCHAR)
                        .param("toDate", toDate.toString(), Types.VARCHAR)
                        .param("zone", zoneId.getId(), Types.VARCHAR)
                        .param("fromInstant", OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC))
                        .param(
                                "toExclusiveInstant",
                                OffsetDateTime.ofInstant(toExclusiveInstant, ZoneOffset.UTC))
                        .query(
                                (rs, rowNum) ->
                                        new AccountTrendRawRow(
                                                rs.getObject("bucket", LocalDate.class),
                                                rs.getLong("tx_count"),
                                                rs.getBigDecimal("total_amount"),
                                                rs.getBigDecimal("total_fee"),
                                                rs.getBigDecimal("bucket_closing_balance"),
                                                rs.getBigDecimal("initial_balance")))
                        .list();

        List<TrendPointResponse> result = new ArrayList<>(rawRows.size());
        BigDecimal carryBalance =
                rawRows.isEmpty()
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : scaleMoney(rawRows.getFirst().initialBalance());
        for (AccountTrendRawRow row : rawRows) {
            if (row.bucketClosingBalance() != null) {
                carryBalance = scaleMoney(row.bucketClosingBalance());
            }
            result.add(
                    new TrendPointResponse(
                            row.bucket(),
                            row.txCount(),
                            scaleMoney(row.totalAmount()),
                            scaleMoney(row.totalFee()),
                            carryBalance));
        }
        return result;
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record AccountTrendRawRow(
            LocalDate bucket,
            long txCount,
            BigDecimal totalAmount,
            BigDecimal totalFee,
            BigDecimal bucketClosingBalance,
            BigDecimal initialBalance) {}
}
