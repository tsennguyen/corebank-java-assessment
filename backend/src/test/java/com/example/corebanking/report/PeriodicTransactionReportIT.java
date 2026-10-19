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
import com.example.corebanking.support.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 17 Testcontainers integration test for Story 4.2 (Periodic Transaction Reports: L3-01,
 * L3-02, G-05, FR-19).
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class PeriodicTransactionReportIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUpFixtures() {
        // Clean any transactions in 2026 inside the test transaction so hand-calculated numbers
        // for 2026-01-01..2026-06-30 are 100% deterministic even if R__demo_seed.sql ran earlier
        jdbcClient
                .sql(
                        "DELETE FROM core.transaction WHERE created_at >= '2025-12-31T00:00:00Z'"
                                + " AND created_at < '2027-01-02T00:00:00Z'")
                .update();

        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990421",
                                "Nguyễn Báo Cáo Kỳ",
                                "000999888421",
                                "report42@example.com",
                                "0900000421",
                                "Số 42 Quận 1",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1990, 4, 2)));

        accountA =
                accountRepository.save(
                        new Account(
                                "100999999421",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
        accountB =
                accountRepository.save(
                        new Account(
                                "100999999422",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                LocalDate.of(2025, 1, 1)));
    }

    @Test
    @Transactional
    @DisplayName(
            "QUARTER report aggregates txCount, total, average, max, min, totalFee across Q1 and"
                    + " Q2, excludes REVERSED and REVERSAL, and assigns 23:30 UTC on Mar 31 to Q2"
                    + " in Asia/Ho_Chi_Minh (AC 1, AC 2, L3-01, L3-02)")
    void quarterReport_withFixedDatasetAndTimezoneBoundary_shouldMatchHandCalculation()
            throws Exception {
        // Insert controlled dataset across Q1 and Q2 2026:
        // Q1 2026 (2026-01-01 .. 2026-03-31 VN time):
        // 1) DEPOSIT  10,000,000.00 fee=0.00    at 2026-01-15T10:00:00+07:00 (COMPLETED) -> Q1
        // 2) TRANSFER  2,000,000.00 fee=2000.00 at 2026-02-10T14:00:00+07:00 (COMPLETED) -> Q1
        // 3) TRANSFER  3,000,000.00 fee=4000.00 at 2026-03-20T09:00:00+07:00 (COMPLETED) -> Q1
        // 4) TRANSFER 99,000,000.00 fee=9000.00 at 2026-02-15T11:00:00+07:00 (REVERSED)  ->
        // EXCLUDED
        // 5) REVERSAL 99,000,000.00 fee=0.00    at 2026-02-15T11:05:00+07:00 (COMPLETED) ->
        // EXCLUDED
        // Hand calculation for Q1 (periodStart = 2026-01-01):
        // - txCount       = 3
        // - totalAmount   = 15,000,000.00
        // - averageAmount =  5,000,000.00
        // - maxAmount     = 10,000,000.00
        // - minAmount     =  2,000,000.00
        // - totalFee      =      6,000.00
        //
        // Q2 2026 (2026-04-01 .. 2026-06-30 VN time):
        // 6) TRANSFER  4,000,000.00 fee=6000.00 at 2026-03-31T23:30:00Z (=
        // 2026-04-01T06:30:00+07:00) -> Q2!
        // 7) PAYMENT   1,000,000.00 fee=1000.00 at 2026-05-12T08:00:00+07:00 (COMPLETED) -> Q2
        // 8) WITHDRAWAL 2,000,000.00 fee=3000.00 at 2026-06-30T23:59:59+07:00 (COMPLETED) -> Q2
        // Hand calculation for Q2 (periodStart = 2026-04-01):
        // - txCount       = 3
        // - totalAmount   = 7,000,000.00
        // - averageAmount = 2,333,333.33 (7,000,000 / 3 rounded HALF_UP to scale 2)
        // - maxAmount     = 4,000,000.00
        // - minAmount     = 1,000,000.00
        // - totalFee      =    10,000.00
        jdbcClient
                .sql(
                        """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-RPT-Q1-1', 'DEPOSIT',    NULL, :accA, 10000000.00, 0.00,    NULL,        10000000.00,  10000000.00, 'TP. HCM', 'Q1 Deposit',  'COMPLETED', '2026-01-15T10:00:00+07:00'),
('TXN-RPT-Q1-2', 'TRANSFER',   :accA, :accB, 2000000.00, 2000.00,  7998000.00,  2000000.00,     -2000.00, 'TP. HCM', 'Q1 Transfer 1', 'COMPLETED', '2026-02-10T14:00:00+07:00'),
('TXN-RPT-Q1-3', 'TRANSFER',   :accA, :accB, 3000000.00, 4000.00,  4994000.00,  5000000.00,     -4000.00, 'TP. HCM', 'Q1 Transfer 2', 'COMPLETED', '2026-03-20T09:00:00+07:00'),
('TXN-RPT-Q1-R1','TRANSFER',   :accA, :accB, 99000000.00, 9000.00, 4994000.00,  5000000.00,     -9000.00, 'TP. HCM', 'Reversed Tx',   'REVERSED',  '2026-02-15T11:00:00+07:00'),
('TXN-RPT-Q1-R2','REVERSAL',   :accB, :accA, 99000000.00, 0.00,    5000000.00,  4994000.00,      9000.00, 'TP. HCM', 'Reversal Entry','COMPLETED', '2026-02-15T11:05:00+07:00'),
('TXN-RPT-Q2-1', 'TRANSFER',   :accA, :accB, 4000000.00, 6000.00,   988000.00,  9000000.00,     -6000.00, 'TP. HCM', 'Boundary 23:30 UTC', 'COMPLETED', '2026-03-31T23:30:00Z'),
('TXN-RPT-Q2-2', 'PAYMENT',    :accB, NULL,  1000000.00, 1000.00,  7999000.00, NULL,         -1001000.00, 'TP. HCM', 'Q2 Payment',    'COMPLETED', '2026-05-12T08:00:00+07:00'),
('TXN-RPT-Q2-3', 'WITHDRAWAL', :accB, NULL,  2000000.00, 3000.00,  5996000.00, NULL,         -2003000.00, 'TP. HCM', 'Q2 Withdrawal', 'COMPLETED', '2026-06-30T23:59:59+07:00')
""")
                .param("accA", accountA.getId())
                .param("accB", accountB.getId())
                .update();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "QUARTER")
                                .param("from", "2026-01-01")
                                .param("to", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$[0].txCount").value(3))
                .andExpect(jsonPath("$[0].totalAmount").value(15000000.00))
                .andExpect(jsonPath("$[0].averageAmount").value(5000000.00))
                .andExpect(jsonPath("$[0].maxAmount").value(10000000.00))
                .andExpect(jsonPath("$[0].minAmount").value(2000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(6000.00))
                .andExpect(jsonPath("$[1].periodStart").value("2026-04-01"))
                .andExpect(jsonPath("$[1].txCount").value(3))
                .andExpect(jsonPath("$[1].totalAmount").value(7000000.00))
                .andExpect(jsonPath("$[1].averageAmount").value(2333333.33))
                .andExpect(jsonPath("$[1].maxAmount").value(4000000.00))
                .andExpect(jsonPath("$[1].minAmount").value(1000000.00))
                .andExpect(jsonPath("$[1].totalFee").value(10000.00));

        // Verify 0 JPA entity queries were executed (pure JdbcClient SQL aggregation, Rule 5 & 16)
        assertThat(stats.getPrepareStatementCount())
                .as("Periodic report uses JdbcClient directly without loading JPA entities")
                .isZero();
    }

    @Test
    @Transactional
    @DisplayName(
            "WEEK and YEAR reports group by ISO Monday / Jan 1 and filter by TransactionType,"
                    + " while invalid range (> 366 days or to < from) returns 400 (AC 1, AC 2,"
                    + " AC 3)")
    void weekAndYearReport_withTypeFilterAndExplainPlan_shouldGroupFilterAndUseIndex()
            throws Exception {
        // Insert transactions in two consecutive ISO weeks of April 2026:
        // - 2026-04-01 (Wednesday) -> ISO week starting Monday 2026-03-30
        // - 2026-04-05 (Sunday 23:00+07:00) -> still ISO week starting Monday 2026-03-30
        // - 2026-04-06 (Monday 00:15+07:00) -> ISO week starting Monday 2026-04-06
        jdbcClient
                .sql(
                        """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-WK-1', 'TRANSFER', :accA, :accB, 1000000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'Wed W1', 'COMPLETED', '2026-04-01T10:00:00+07:00'),
('TXN-WK-2', 'TRANSFER', :accA, :accB, 3000000.00, 4000.00, 10000000.00, 10000000.00, -4000.00, 'Ha Noi', 'Sun W1', 'COMPLETED', '2026-04-05T23:00:00+07:00'),
('TXN-WK-3', 'DEPOSIT',  NULL,  :accA, 9000000.00, 0.00,    NULL,        19000000.00, 9000000.00, 'Ha Noi', 'Dep W1', 'COMPLETED', '2026-04-03T12:00:00+07:00'),
('TXN-WK-4', 'TRANSFER', :accA, :accB, 5000000.00, 6000.00, 10000000.00, 10000000.00, -6000.00, 'Ha Noi', 'Mon W2', 'COMPLETED', '2026-04-06T00:15:00+07:00')
""")
                .param("accA", accountA.getId())
                .param("accB", accountB.getId())
                .update();

        // 1. WEEK report with type=TRANSFER (excludes DEPOSIT TXN-WK-3)
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "WEEK")
                                .param("from", "2026-04-01")
                                .param("to", "2026-04-12")
                                .param("type", "TRANSFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].periodStart").value("2026-03-30"))
                .andExpect(jsonPath("$[0].txCount").value(2))
                .andExpect(jsonPath("$[0].totalAmount").value(4000000.00))
                .andExpect(jsonPath("$[0].averageAmount").value(2000000.00))
                .andExpect(jsonPath("$[0].maxAmount").value(3000000.00))
                .andExpect(jsonPath("$[0].minAmount").value(1000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(6000.00))
                .andExpect(jsonPath("$[1].periodStart").value("2026-04-06"))
                .andExpect(jsonPath("$[1].txCount").value(1))
                .andExpect(jsonPath("$[1].totalAmount").value(5000000.00));

        // 2. YEAR report without type filter (includes all 4 transactions in 2026-01-01 bucket)
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "YEAR")
                                .param("from", "2026-01-01")
                                .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$[0].txCount").value(4))
                .andExpect(jsonPath("$[0].totalAmount").value(18000000.00))
                .andExpect(jsonPath("$[0].averageAmount").value(4500000.00))
                .andExpect(jsonPath("$[0].maxAmount").value(9000000.00))
                .andExpect(jsonPath("$[0].minAmount").value(1000000.00))
                .andExpect(jsonPath("$[0].totalFee").value(12000.00));

        // 3. Range > 366 days -> 400 VALIDATION_FAILED
        mockMvc.perform(
                        get("/api/v1/reports/transactions")
                                .param("period", "YEAR")
                                .param("from", "2025-01-01")
                                .param("to", "2026-01-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 4. Verify EXPLAIN (ANALYZE, BUFFERS) plan uses Index Scan on core.transaction (Rule 6)
        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> explainLines =
                    jdbcClient
                            .sql(
                                    """
EXPLAIN (ANALYZE, BUFFERS)
SELECT (date_trunc('quarter', t.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'))::date AS period_start,
       COUNT(*) AS tx_count,
       COALESCE(SUM(t.amount), 0.00) AS total_amount,
       COALESCE(ROUND(AVG(t.amount), 2), 0.00) AS average_amount,
       COALESCE(MAX(t.amount), 0.00) AS max_amount,
       COALESCE(MIN(t.amount), 0.00) AS min_amount,
       COALESCE(SUM(t.fee), 0.00) AS total_fee
FROM core.transaction t
WHERE t.status = 'COMPLETED'
  AND t.type <> 'REVERSAL'
  AND t.created_at >= '2026-01-01T00:00:00+07:00'
  AND t.created_at < '2026-07-01T00:00:00+07:00'
GROUP BY 1
ORDER BY 1 ASC
""")
                            .query(String.class)
                            .list();
            String explainPlan = String.join("\n", explainLines);
            assertThat(explainPlan)
                    .contains("Index Scan")
                    .doesNotContain("Seq Scan on transaction");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }
    }
}
