package com.example.corebanking.report.infrastructure;

import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.report.api.dto.BalanceTierStatisticResponse;
import com.example.corebanking.report.api.dto.LocationStatisticResponse;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only reporting repository executing SQL aggregations directly in PostgreSQL via JdbcClient
 * without loading entities into JVM memory (L1-09, L1-10, G-05).
 */
@Repository
@RequiredArgsConstructor
public class StatisticsRepository {

    private static final String BALANCE_TIER_STATS_SQL =
            """
            WITH tiers(tier, sort_order) AS (
                VALUES ('LOW', 1), ('MEDIUM', 2), ('HIGH', 3)
            ),
            classified_accounts AS (
                SELECT a.id,
                       CASE
                           WHEN a.balance < :mediumThreshold THEN 'LOW'
                           WHEN a.balance < :highThreshold THEN 'MEDIUM'
                           ELSE 'HIGH'
                       END AS tier
                FROM core.account a
                WHERE a.status <> 'CLOSED'
            ),
            tx_per_account AS (
                SELECT account_id, COUNT(*) AS tx_count
                FROM (
                    SELECT source_account_id AS account_id
                    FROM core.transaction
                    WHERE source_account_id IS NOT NULL
                    UNION ALL
                    SELECT target_account_id AS account_id
                    FROM core.transaction
                    WHERE target_account_id IS NOT NULL
                ) tx_refs
                GROUP BY account_id
            ),
            tier_aggregates AS (
                SELECT ca.tier,
                       COUNT(ca.id) AS account_count,
                       CAST(COALESCE(SUM(tpa.tx_count), 0) AS BIGINT) AS transaction_count
                FROM classified_accounts ca
                LEFT JOIN tx_per_account tpa ON tpa.account_id = ca.id
                GROUP BY ca.tier
            )
            SELECT tr.tier AS tier,
                   COALESCE(ta.account_count, 0) AS account_count,
                   COALESCE(ta.transaction_count, 0) AS transaction_count
            FROM tiers tr
            LEFT JOIN tier_aggregates ta ON ta.tier = tr.tier
            ORDER BY tr.sort_order
            """;

    private static final String CUSTOMERS_BY_LOCATION_SQL =
            """
            SELECT COALESCE(c.location, 'UNKNOWN') AS location,
                   COUNT(*) AS customer_count
            FROM core.customer c
            WHERE c.deleted_at IS NULL
            GROUP BY c.location
            ORDER BY customer_count DESC, location ASC
            """;

    private final JdbcClient jdbcClient;

    /**
     * Aggregates non-closed account counts and associated transaction counts across LOW, MEDIUM,
     * and HIGH balance tiers in a single database round-trip.
     *
     * @param mediumThreshold minimum balance for MEDIUM tier
     * @param highThreshold minimum balance for HIGH tier
     * @return ordered list of balance tier statistics (LOW, MEDIUM, HIGH)
     */
    public List<BalanceTierStatisticResponse> findBalanceTierStatistics(
            BigDecimal mediumThreshold, BigDecimal highThreshold) {
        return jdbcClient
                .sql(BALANCE_TIER_STATS_SQL)
                .param("mediumThreshold", mediumThreshold)
                .param("highThreshold", highThreshold)
                .query(
                        (rs, rowNum) ->
                                new BalanceTierStatisticResponse(
                                        BalanceTier.valueOf(rs.getString("tier")),
                                        rs.getLong("account_count"),
                                        rs.getLong("transaction_count")))
                .list();
    }

    /**
     * Aggregates active (non-soft-deleted) customer counts grouped by location, sorted descending
     * by count.
     *
     * @return list of location statistics
     */
    public List<LocationStatisticResponse> findCustomerCountByLocation() {
        return jdbcClient
                .sql(CUSTOMERS_BY_LOCATION_SQL)
                .query(
                        (rs, rowNum) ->
                                new LocationStatisticResponse(
                                        rs.getString("location"), rs.getLong("customer_count")))
                .list();
    }
}
