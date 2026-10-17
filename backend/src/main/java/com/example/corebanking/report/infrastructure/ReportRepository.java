package com.example.corebanking.report.infrastructure;

import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only reporting repository executing periodic transaction aggregations directly in PostgreSQL
 * via {@link JdbcClient} and {@code date_trunc} without loading transaction rows into JVM memory
 * (L3-01, L3-02, G-05, FR-19).
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

    private static BigDecimal scaleMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
