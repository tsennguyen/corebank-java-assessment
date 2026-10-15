package com.example.corebanking.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 17 Testcontainers integration test for Story 3.3 (Advanced Transaction Search: L2-03,
 * L2-04, G-05, R3-11, Rule 16).
 */
@AutoConfigureMockMvc
class TransactionSearchIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + password
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    @Test
    @Transactional
    @DisplayName(
            "Multi-filter transaction search combines AND predicates, enforces VN timezone"
                    + " boundaries, sorts by amount/fee/createdAt, isolates CUSTOMER ownership,"
                    + " escapes LIKE wildcards, and executes <= 2 SQL queries (AC 1–4, R3-11)")
    void advancedTransactionSearch_shouldFilterSortIsolateAndPreventNPlusOne() throws Exception {
        String seedSql =
                new ClassPathResource("db/seed/R__demo_seed.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbcClient.sql(seedSql).update();

        String adminToken = loginAndGetToken("admin", "Admin@123");
        String tokenAn = loginAndGetToken("cust.an", "Customer@123");

        Long accountAnId =
                jdbcClient
                        .sql("SELECT id FROM core.account WHERE account_number = '100800000018'")
                        .query(Long.class)
                        .single();
        Long accountBinhId =
                jdbcClient
                        .sql("SELECT id FROM core.account WHERE account_number = '100800000026'")
                        .query(Long.class)
                        .single();
        Long accountCuongId =
                jdbcClient
                        .sql("SELECT id FROM core.account WHERE account_number = '100800000034'")
                        .query(Long.class)
                        .single();

        // Insert controlled September 2026 transactions with exact Asia/Ho_Chi_Minh boundary times:
        // 1) 2026-08-31 23:59:59+07:00 (1s before Sept 1 VN time -> must be EXCLUDED)
        // 2) 2026-09-01 00:00:00+07:00 (exact start of Sept 1 VN time, amount=1,500,000, fee=2,000
        // -> INCLUDED)
        // 3) 2026-09-15 12:00:00+07:00 (amount=4,000,000, fee=6,000 -> INCLUDED)
        // 4) 2026-09-30 23:59:59.500+07:00 (500ms before end of Sept 30 VN time, amount=2,500,000,
        // fee=2,000 -> INCLUDED)
        // 5) 2026-10-01 00:00:00+07:00 (exact start of Oct 1 VN time -> must be EXCLUDED)
        // 6) 2026-09-20 10:00:00+07:00 (REVERSED status -> excluded when status=COMPLETED)
        // 7) 2026-09-22 10:00:00+07:00 (Binh -> Cuong transfer, amount=3,000,000 -> visible to
        // Admin, hidden from An)
        jdbcClient
                .sql(
                        """
INSERT INTO core.transaction (
    reference_no, type, source_account_id, target_account_id,
    amount, fee, source_balance_after, target_balance_after,
    system_balance_delta, location, description, status, created_at
) VALUES
('TXN-SEP-BEFORE', 'TRANSFER', :anId, :binhId, 2000000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'Before Sept', 'COMPLETED', '2026-08-31T23:59:59+07:00'),
('TXN-SEP-START',  'TRANSFER', :anId, :binhId, 1500000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'Start of Sept', 'COMPLETED', '2026-09-01T00:00:00+07:00'),
('TXN-SEP-MID',    'TRANSFER', :anId, :binhId, 4000000.00, 6000.00, 10000000.00, 10000000.00, -6000.00, 'Ha Noi', 'Mid Sept', 'COMPLETED', '2026-09-15T12:00:00+07:00'),
('TXN-SEP-END-MS', 'TRANSFER', :anId, :binhId, 2500000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'End of Sept 500ms', 'COMPLETED', '2026-09-30T23:59:59.500+07:00'),
('TXN-OCT-START',  'TRANSFER', :anId, :binhId, 2000000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'Start of Oct', 'COMPLETED', '2026-10-01T00:00:00+07:00'),
('TXN-SEP-REV',    'TRANSFER', :anId, :binhId, 3500000.00, 2000.00, 10000000.00, 10000000.00, -2000.00, 'Ha Noi', 'Reversed Sept', 'REVERSED', '2026-09-20T10:00:00+07:00'),
('TXN-SEP-OTHER',  'TRANSFER', :binhId, :cuongId, 3000000.00, 4000.00, 10000000.00, 10000000.00, -4000.00, 'Da Nang', 'Binh to Cuong', 'COMPLETED', '2026-09-22T10:00:00+07:00')
""")
                .param("anId", accountAnId)
                .param("binhId", accountBinhId)
                .param("cuongId", accountCuongId)
                .update();

        // 1. AC 1: Admin multi-filter search in Sept 2026 with status=COMPLETED, minAmount=1M,
        // maxAmount=5M, sort=amount,desc -> matches TXN-SEP-MID (4M), TXN-SEP-OTHER (3M),
        // TXN-SEP-END-MS (2.5M), TXN-SEP-START (1.5M)
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("minAmount", "1000000")
                                .param("maxAmount", "5000000")
                                .param("type", "TRANSFER")
                                .param("status", "COMPLETED")
                                .param("fromDate", "2026-09-01")
                                .param("toDate", "2026-09-30")
                                .param("page", "0")
                                .param("size", "10")
                                .param("sort", "amount,desc")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.content[0].referenceNo").value("TXN-SEP-MID"))
                .andExpect(jsonPath("$.content[1].referenceNo").value("TXN-SEP-OTHER"))
                .andExpect(jsonPath("$.content[2].referenceNo").value("TXN-SEP-END-MS"))
                .andExpect(jsonPath("$.content[3].referenceNo").value("TXN-SEP-START"));

        // 2. AC 2: Sort by fee,desc -> TXN-SEP-MID (6000) first, TXN-SEP-OTHER (4000) second
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("type", "TRANSFER")
                                .param("status", "COMPLETED")
                                .param("fromDate", "2026-09-01")
                                .param("toDate", "2026-09-30")
                                .param("sort", "fee,desc")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].referenceNo").value("TXN-SEP-MID"))
                .andExpect(jsonPath("$.content[0].fee").value(6000.00))
                .andExpect(jsonPath("$.content[1].referenceNo").value("TXN-SEP-OTHER"))
                .andExpect(jsonPath("$.content[1].fee").value(4000.00));

        // 3. AC 2: Invalid sort field or inverted filter ranges return 400
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("sort", "description,asc")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_FIELD"));

        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("minAmount", "5000000")
                                .param("maxAmount", "1000000")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("fromDate", "2026-09-30")
                                .param("toDate", "2026-09-01")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 4. AC 3: Customer An running the exact same Sept 2026 search only sees An's 3 transfers
        // (TXN-SEP-OTHER between Binh and Cuong is automatically excluded)
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("minAmount", "1000000")
                                .param("maxAmount", "5000000")
                                .param("type", "TRANSFER")
                                .param("status", "COMPLETED")
                                .param("fromDate", "2026-09-01")
                                .param("toDate", "2026-09-30")
                                .param("sort", "amount,desc")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].referenceNo").value("TXN-SEP-MID"))
                .andExpect(jsonPath("$.content[1].referenceNo").value("TXN-SEP-END-MS"))
                .andExpect(jsonPath("$.content[2].referenceNo").value("TXN-SEP-START"));

        // Customer An passing Binh's accountId gets 403 ACCESS_DENIED
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("accountId", String.valueOf(accountBinhId))
                                .param("fromDate", "2026-09-01")
                                .param("toDate", "2026-09-30")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 5. R3-11 (1): SQL LIKE wildcard escaping on GET /api/v1/customers
        mockMvc.perform(
                        get("/api/v1/customers")
                                .param("name", "%")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(
                        get("/api/v1/customers")
                                .param("location", "_")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // 6. R3-11 (2) & performance.md Rule 16: Assert SQL query count <= 2 via Hibernate
        // Statistics (0 N+1 queries)
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("page", "0")
                                .param("size", "20")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(stats.getPrepareStatementCount())
                .as("GET /api/v1/transactions (Admin) must execute at most 2 SQL queries (no N+1)")
                .isLessThanOrEqualTo(2L);

        stats.clear();
        mockMvc.perform(
                        get("/api/v1/customers")
                                .param("page", "0")
                                .param("size", "20")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(stats.getPrepareStatementCount())
                .as("GET /api/v1/customers must execute at most 2 SQL queries (no N+1)")
                .isLessThanOrEqualTo(2L);

        stats.clear();
        mockMvc.perform(
                        get("/api/v1/accounts")
                                .param("page", "0")
                                .param("size", "20")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(stats.getPrepareStatementCount())
                .as("GET /api/v1/accounts must execute at most 2 SQL queries (no N+1)")
                .isLessThanOrEqualTo(2L);

        // 7. AC 4: Verify EXPLAIN plan for account + date range query and status query uses index
        // scan on PG17
        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> explainLines =
                    jdbcClient
                            .sql(
                                    """
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, reference_no, type, status, amount, fee, created_at
FROM core.transaction
WHERE (source_account_id = :accId OR target_account_id = :accId)
  AND created_at >= '2026-09-01T00:00:00+07:00'
  AND created_at <  '2026-10-01T00:00:00+07:00'
ORDER BY created_at DESC, id DESC
LIMIT 10
""")
                            .param("accId", accountAnId)
                            .query(String.class)
                            .list();
            String explainPlan = String.join("\n", explainLines);
            assertThat(explainPlan)
                    .contains("Index Scan")
                    .doesNotContain("Seq Scan on transaction");

            List<String> statusExplainLines =
                    jdbcClient
                            .sql(
                                    """
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, reference_no, status, amount, created_at
FROM core.transaction
WHERE status = 'REVERSED'
ORDER BY created_at DESC
LIMIT 10
""")
                            .query(String.class)
                            .list();
            String statusExplainPlan = String.join("\n", statusExplainLines);
            assertThat(statusExplainPlan).contains("idx_tx_status_created");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }
    }
}
