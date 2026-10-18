package com.example.corebanking.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.report.api.dto.ReportBundle;
import com.example.corebanking.report.application.ReportDataService;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.report.export.ChartRenderer;
import com.example.corebanking.support.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 17 Testcontainers integration test for Story 4.5 (Trend Data and Chart Rendering:
 * L3-08, L3-10, FR-20, ADR-013, ADR-014).
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class TrendReportIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ReportDataService reportDataService;
    @Autowired private ChartRenderer chartRenderer;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Account accountA;
    private Account accountB;

    private void setUpIsolatedFixtures() {
        // Isolate test dataset inside the test transaction so total balance and historical
        // deltas are 100% deterministic
        jdbcClient.sql("DELETE FROM core.alert").update();
        jdbcClient.sql("DELETE FROM core.transaction").update();
        jdbcClient.sql("DELETE FROM core.recurring_transaction").update();
        jdbcClient.sql("DELETE FROM core.account_status_history").update();
        jdbcClient.sql("DELETE FROM core.account").update();

        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990451",
                                "Nguyễn Xu Hướng",
                                "000999888451",
                                "trend45@example.com",
                                "0900000451",
                                "Số 45 Quận 1",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1991, 4, 5)));

        accountA =
                accountRepository.save(
                        new Account(
                                "100999999451",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        accountB =
                accountRepository.save(
                        new Account(
                                "100999999452",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
    }

    @Test
    @Transactional
    @DisplayName(
            "System-wide trend series reconstructs historical closing balances from current"
                    + " balance minus subsequent system_balance_delta across active and sparse"
                    + " buckets (AC 1, L3-08, ADR-013)")
    void systemTrends_shouldReconstructHistoricalClosingBalancesUsingSystemBalanceDelta()
            throws Exception {
        setUpIsolatedFixtures();
        // Timeline across 2026-10-01 .. 2026-10-04 (Asia/Ho_Chi_Minh):
        // - 2026-10-01: DEPOSIT +20,000,000 into A (fee 0, sys_delta = +20,000,000.00)
        //               -> end of 2026-10-01 system balance = 20,000,000.00
        // - 2026-10-02: No transactions (sparse bucket)
        //               -> end of 2026-10-02 system balance = 20,000,000.00
        // - 2026-10-03: TRANSFER 5,000,000 from A to B (fee 2,000, sys_delta = -2,000.00)
        //               + WITHDRAWAL 3,000,000 from B (fee 2,000, sys_delta = -3,002,000.00)
        //               -> bucket delta = -3,004,000.00
        //               -> end of 2026-10-03 system balance = 16,996,000.00
        // - 2026-10-04: DEPOSIT +10,000,000 into B (fee 0, sys_delta = +10,000,000.00)
        //               -> end of 2026-10-04 (current) system balance = 26,996,000.00
        // Final account balances:
        // - accountA: 20,000,000 - 5,002,000 = 14,998,000.00
        // - accountB: 5,000,000 - 3,002,000 + 10,000,000 = 11,998,000.00
        // Total current system balance = 26,996,000.00
        jdbcClient
                .sql("UPDATE core.account SET balance = 14998000.00 WHERE id = :id")
                .param("id", accountA.getId())
                .update();
        jdbcClient
                .sql("UPDATE core.account SET balance = 11998000.00 WHERE id = :id")
                .param("id", accountB.getId())
                .update();

        jdbcClient
                .sql(
                        """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-TRD-1', 'DEPOSIT',    NULL,  :accA, 20000000.00, 0.00,    NULL,        20000000.00,  20000000.00, 'TP. HCM', 'Day 1 Deposit',    'COMPLETED', '2026-10-01T09:00:00+07:00'),
('TXN-TRD-2', 'TRANSFER',   :accA, :accB,  5000000.00, 2000.00, 14998000.00,  5000000.00,     -2000.00, 'TP. HCM', 'Day 3 Transfer',   'COMPLETED', '2026-10-03T10:00:00+07:00'),
('TXN-TRD-3', 'WITHDRAWAL', :accB, NULL,   3000000.00, 2000.00,  1998000.00, NULL,         -3002000.00, 'TP. HCM', 'Day 3 Withdrawal', 'COMPLETED', '2026-10-03T15:00:00+07:00'),
('TXN-TRD-4', 'DEPOSIT',    NULL,  :accB, 10000000.00, 0.00,    NULL,        11998000.00,  10000000.00, 'TP. HCM', 'Day 4 Deposit',    'COMPLETED', '2026-10-04T11:00:00+07:00')
""")
                .param("accA", accountA.getId())
                .param("accB", accountB.getId())
                .update();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "DAY")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                // Day 1: 2026-10-01
                .andExpect(jsonPath("$[0].bucket").value("2026-10-01"))
                .andExpect(jsonPath("$[0].txCount").value(1))
                .andExpect(jsonPath("$[0].totalAmount").value(20000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(0.00))
                .andExpect(jsonPath("$[0].closingBalance").value(20000000.00))
                // Day 2: 2026-10-02 (sparse bucket carries 20,000,000.00)
                .andExpect(jsonPath("$[1].bucket").value("2026-10-02"))
                .andExpect(jsonPath("$[1].txCount").value(0))
                .andExpect(jsonPath("$[1].totalAmount").value(0.00))
                .andExpect(jsonPath("$[1].totalFee").value(0.00))
                .andExpect(jsonPath("$[1].closingBalance").value(20000000.00))
                // Day 3: 2026-10-03
                .andExpect(jsonPath("$[2].bucket").value("2026-10-03"))
                .andExpect(jsonPath("$[2].txCount").value(2))
                .andExpect(jsonPath("$[2].totalAmount").value(8000000.00))
                .andExpect(jsonPath("$[2].totalFee").value(4000.00))
                .andExpect(jsonPath("$[2].closingBalance").value(16996000.00))
                // Day 4: 2026-10-04 (matches current total balance 26,996,000.00)
                .andExpect(jsonPath("$[3].bucket").value("2026-10-04"))
                .andExpect(jsonPath("$[3].txCount").value(1))
                .andExpect(jsonPath("$[3].totalAmount").value(10000000.00))
                .andExpect(jsonPath("$[3].totalFee").value(0.00))
                .andExpect(jsonPath("$[3].closingBalance").value(26996000.00));

        assertThat(stats.getPrepareStatementCount())
                .as("System trend query executes in JdbcClient with 0 JPA entity queries")
                .isZero();
    }

    @Test
    @Transactional
    @DisplayName(
            "Single-account trend series returns last balance_after per bucket and carries"
                    + " forward balance across buckets without transactions (AC 2, L3-08)")
    void accountTrends_withAccountId_shouldReturnAccountSpecificClosingBalances() throws Exception {
        setUpIsolatedFixtures();
        // Account A timeline:
        // - Prior to 2026-10-01 (on 2026-09-30): DEPOSIT 10,000,000 -> balance_after =
        // 10,000,000.00
        // - 2026-10-01: no transactions -> carries forward 10,000,000.00
        // - 2026-10-02:
        //   1) DEPOSIT +5,000,000 (10:00) -> target_balance_after = 15,000,000.00
        //   2) WITHDRAWAL 2,000,000 + fee 2,000 (16:00) -> source_balance_after = 12,998,000.00
        //   -> bucket closingBalance = 12,998,000.00 (last transaction in bucket)
        // - 2026-10-03: no transactions -> carries forward 12,998,000.00
        // - 2026-10-04: TRANSFER 1,000,000 from B to A -> target_balance_after = 13,998,000.00
        jdbcClient
                .sql("UPDATE core.account SET balance = 13998000.00 WHERE id = :id")
                .param("id", accountA.getId())
                .update();

        jdbcClient
                .sql(
                        """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-ACC-0', 'DEPOSIT',    NULL,  :accA, 10000000.00, 0.00,    NULL,        10000000.00, 10000000.00, 'Ha Noi', 'Prior Deposit', 'COMPLETED', '2026-09-30T15:00:00+07:00'),
('TXN-ACC-1', 'DEPOSIT',    NULL,  :accA,  5000000.00, 0.00,    NULL,        15000000.00,  5000000.00, 'Ha Noi', 'Morning Dep',   'COMPLETED', '2026-10-02T10:00:00+07:00'),
('TXN-ACC-2', 'WITHDRAWAL', :accA, NULL,   2000000.00, 2000.00, 12998000.00, NULL,        -2002000.00, 'Ha Noi', 'Afternoon Wd',  'COMPLETED', '2026-10-02T16:00:00+07:00'),
('TXN-ACC-3', 'TRANSFER',   :accB, :accA,  1000000.00, 2000.00,  8998000.00, 13998000.00,    -2000.00, 'Ha Noi', 'Inbound Tf',    'COMPLETED', '2026-10-04T11:00:00+07:00')
""")
                .param("accA", accountA.getId())
                .param("accB", accountB.getId())
                .update();

        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "DAY")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-04")
                                .param("accountId", accountA.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                // 2026-10-01: carries prior 10,000,000.00
                .andExpect(jsonPath("$[0].bucket").value("2026-10-01"))
                .andExpect(jsonPath("$[0].txCount").value(0))
                .andExpect(jsonPath("$[0].closingBalance").value(10000000.00))
                // 2026-10-02: 2 transactions, last balance_after = 12,998,000.00
                .andExpect(jsonPath("$[1].bucket").value("2026-10-02"))
                .andExpect(jsonPath("$[1].txCount").value(2))
                .andExpect(jsonPath("$[1].totalAmount").value(7000000.00))
                .andExpect(jsonPath("$[1].totalFee").value(2000.00))
                .andExpect(jsonPath("$[1].closingBalance").value(12998000.00))
                // 2026-10-03: 0 transactions, carries forward 12,998,000.00
                .andExpect(jsonPath("$[2].bucket").value("2026-10-03"))
                .andExpect(jsonPath("$[2].txCount").value(0))
                .andExpect(jsonPath("$[2].closingBalance").value(12998000.00))
                // 2026-10-04: inbound transfer -> target_balance_after = 13,998,000.00
                .andExpect(jsonPath("$[3].bucket").value("2026-10-04"))
                .andExpect(jsonPath("$[3].txCount").value(1))
                .andExpect(jsonPath("$[3].totalAmount").value(1000000.00))
                .andExpect(jsonPath("$[3].closingBalance").value(13998000.00));

        // Non-existent accountId -> 404 RESOURCE_NOT_FOUND
        mockMvc.perform(
                        get("/api/v1/reports/trends")
                                .param("granularity", "DAY")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-04")
                                .param("accountId", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName(
            "ReportDataService loads periodic summary and trends in parallel on Virtual Threads,"
                + " ChartRenderer generates 800x400 PNGs, and EXPLAIN ANALYZE confirms WindowAgg"
                + " and Index Scan (AC 3, AC 4, Rule 6)")
    void parallelLoadAndChartRendering_withExplainAnalyze_shouldSucceed() throws Exception {
        Customer committedCustomer =
                customerRepository.save(
                        new Customer(
                                "CUS9990459",
                                "Nguyễn Song Song",
                                "000999888459",
                                "trend459@example.com",
                                "0900000459",
                                "Số 459 Quận 1",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1992, 4, 5)));
        Account committedAccount =
                accountRepository.save(
                        new Account(
                                "100999999459",
                                committedCustomer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        try {
            jdbcClient
                    .sql("UPDATE core.account SET balance = 25000000.00 WHERE id = :id")
                    .param("id", committedAccount.getId())
                    .update();
            jdbcClient
                    .sql(
                            """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-BDL-1', 'DEPOSIT', NULL, :accA, 25000000.00, 0.00, NULL, 25000000.00, 25000000.00, 'TP. HCM', 'Bundle Dep', 'COMPLETED', '2027-10-01T10:00:00+07:00')
""")
                    .param("accA", committedAccount.getId())
                    .update();

            ReportBundle bundle =
                    reportDataService.loadReportBundle(
                            ReportPeriod.WEEK,
                            TrendGranularity.DAY,
                            LocalDate.of(2027, 10, 1),
                            LocalDate.of(2027, 10, 3),
                            null,
                            committedAccount.getId());

            assertThat(bundle.periodicBuckets()).hasSize(1);
            assertThat(bundle.trendPoints()).hasSize(3);
            assertThat(bundle.trendPoints().get(0).closingBalance())
                    .isEqualByComparingTo("25000000.00");
            assertThat(bundle.trendPoints().get(1).closingBalance())
                    .isEqualByComparingTo("25000000.00");
            assertThat(bundle.trendPoints().get(2).closingBalance())
                    .isEqualByComparingTo("25000000.00");

            ChartRenderer.RenderedCharts charts =
                    chartRenderer.renderAllCharts(bundle.trendPoints());
            BufferedImage volImg =
                    ImageIO.read(new ByteArrayInputStream(charts.transactionVolumePng()));
            BufferedImage balImg = ImageIO.read(new ByteArrayInputStream(charts.balanceTrendPng()));
            BufferedImage feeImg = ImageIO.read(new ByteArrayInputStream(charts.feeTrendPng()));
            assertThat(volImg.getWidth()).isEqualTo(800);
            assertThat(volImg.getHeight()).isEqualTo(400);
            assertThat(balImg.getWidth()).isEqualTo(800);
            assertThat(balImg.getHeight()).isEqualTo(400);
            assertThat(feeImg.getWidth()).isEqualTo(800);
            assertThat(feeImg.getHeight()).isEqualTo(400);

            // Verify EXPLAIN (ANALYZE, BUFFERS) on system trend query uses WindowAgg + Index Scan
            jdbcClient.sql("SET enable_seqscan = off").update();
            try {
                List<String> explainLines =
                        jdbcClient
                                .sql(
                                        """
EXPLAIN (ANALYZE, BUFFERS)
WITH buckets AS (
    SELECT gs::date AS bucket
    FROM generate_series(
        date_trunc('day', TIMESTAMP '2027-10-01'),
        date_trunc('day', TIMESTAMP '2027-10-03'),
        INTERVAL '1 day'
    ) AS gs
),
tx_by_bucket AS (
    SELECT (date_trunc('day', t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'))::date AS bucket,
           COUNT(*) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL') AS tx_count,
           COALESCE(SUM(t.amount) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_amount,
           COALESCE(SUM(t.fee) FILTER (WHERE t.status = 'COMPLETED' AND t.type <> 'REVERSAL'), 0.00) AS total_fee,
           COALESCE(SUM(t.system_balance_delta), 0.00) AS bucket_delta
    FROM core.transaction t
    WHERE t.created_at >= '2027-10-01T00:00:00+07:00'
      AND t.created_at < '2027-10-04T00:00:00+07:00'
    GROUP BY 1
),
post_range_delta AS (
    SELECT COALESCE(SUM(t.system_balance_delta), 0.00) AS after_to_delta
    FROM core.transaction t
    WHERE t.created_at >= '2027-10-04T00:00:00+07:00'
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
""")
                                .query(String.class)
                                .list();
                String explainPlan = String.join("\n", explainLines);
                assertThat(explainPlan).contains("WindowAgg").contains("Index Scan");
            } finally {
                jdbcClient.sql("RESET enable_seqscan").update();
            }
        } finally {
            jdbcClient
                    .sql("DELETE FROM core.transaction WHERE reference_no = 'TXN-BDL-1'")
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.account WHERE id = :id")
                    .param("id", committedAccount.getId())
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.customer WHERE id = :id")
                    .param("id", committedCustomer.getId())
                    .update();
        }
    }
}
