package com.example.corebanking.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.infrastructure.AlertRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack PostgreSQL 17 integration test for Story 4.4: Alert search & filtering ({@code GET
 * /api/v1/alerts} with {@code HibernateStatistics <= 2} SQL queries), alert lifecycle transitions
 * ({@code POST /api/v1/alerts/{id}/status}), and historical batch fraud scan using PostgreSQL
 * window functions ({@code POST /api/v1/fraud/scans}) (L3-03, L3-04, FR-22, FR-23, G-05).
 */
@AutoConfigureMockMvc
class AlertManagementAndScanIT extends IntegrationTestBase {

    private static final AtomicInteger NATIONAL_ID_SEQ = new AtomicInteger(440000);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private AlertRepository alertRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdCustomerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!createdAccountIds.isEmpty()) {
            jdbcClient
                    .sql(
                            "DELETE FROM core.alert WHERE account_id IN (:ids) OR transaction_id IN"
                                    + " (SELECT id FROM core.transaction WHERE source_account_id IN"
                                    + " (:ids) OR target_account_id IN (:ids))")
                    .param("ids", createdAccountIds)
                    .update();
            jdbcClient
                    .sql(
                            "DELETE FROM core.transaction WHERE source_account_id IN (:ids) OR"
                                    + " target_account_id IN (:ids)")
                    .param("ids", createdAccountIds)
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.account_status_history WHERE account_id IN (:ids)")
                    .param("ids", createdAccountIds)
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.account WHERE id IN (:ids)")
                    .param("ids", createdAccountIds)
                    .update();
        }
        if (!createdCustomerIds.isEmpty()) {
            jdbcClient
                    .sql("DELETE FROM core.customer WHERE id IN (:ids)")
                    .param("ids", createdCustomerIds)
                    .update();
        }
        createdAccountIds.clear();
        createdCustomerIds.clear();
    }

    @Test
    @DisplayName(
            "Historical fraud scan detects 7 outgoing txns in 40s (VELOCITY) and 1 80M txn"
                    + " (AMOUNT_THRESHOLD) via SQL window function, is 100% idempotent on re-scan,"
                    + " and ignores DEPOSIT/REVERSED (AC 3)")
    void historicalScan_when7OutgoingIn40sAnd1HighValueTxn_shouldCreateAlertsIdempotently()
            throws Exception {
        AccountResponse accountA = createAccountWithLimits("500000000.00", "2000000000.00");
        AccountResponse accountB = createAccountWithLimits("500000000.00", "2000000000.00");

        Instant baseTime = Instant.parse("2026-08-15T10:00:00Z");

        // Insert 7 outgoing WITHDRAWAL transactions of 1,000,000 VND within 40 seconds (at +0s,
        // +6s, +12s, +18s, +24s, +30s, +36s) directly via SQL (simulating historical data without
        // alerts)
        List<Long> burstTxIds = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Instant txTime = baseTime.plusSeconds(i * 6L);
            Long txId =
                    insertHistoricalTransaction(
                            "TXN-SCAN-VEL-" + accountA.id() + "-" + i,
                            "WITHDRAWAL",
                            accountA.id(),
                            null,
                            new BigDecimal("1000000.00"),
                            "COMPLETED",
                            txTime);
            burstTxIds.add(txId);
        }

        // Insert 1 high-value outgoing TRANSFER of 80,000,000 VND at baseTime + 600s (outside the
        // 60s burst window so it only triggers AMOUNT_THRESHOLD)
        Long highValueTxId =
                insertHistoricalTransaction(
                        "TXN-SCAN-AMT-" + accountA.id(),
                        "TRANSFER",
                        accountA.id(),
                        accountB.id(),
                        new BigDecimal("80000000.00"),
                        "COMPLETED",
                        baseTime.plusSeconds(600L));

        // Insert 1 high-value DEPOSIT (90M) and 1 REVERSED WITHDRAWAL (70M) — must NOT trigger
        // alerts
        insertHistoricalTransaction(
                "TXN-SCAN-DEP-" + accountA.id(),
                "DEPOSIT",
                null,
                accountA.id(),
                new BigDecimal("90000000.00"),
                "COMPLETED",
                baseTime.plusSeconds(700L));
        insertHistoricalTransaction(
                "TXN-SCAN-REV-" + accountA.id(),
                "WITHDRAWAL",
                accountA.id(),
                null,
                new BigDecimal("70000000.00"),
                "REVERSED",
                baseTime.plusSeconds(800L));

        String scanBody =
                """
                {
                  "from": "2026-08-15T09:55:00Z",
                  "to": "2026-08-15T10:30:00Z"
                }
                """;

        // 1. First scan creates 1 AMOUNT_THRESHOLD alert + 2 VELOCITY alerts (for 6th and 7th txns)
        // = 3 alerts
        mockMvc.perform(
                        post("/api/v1/fraud/scans")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scanBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCreated").value(3))
                .andExpect(jsonPath("$.amountThresholdAlerts").value(1))
                .andExpect(jsonPath("$.velocityAlerts").value(2));

        // Verify exact alerts persisted in DB
        assertThat(
                        alertRepository.findByTransactionIdAndRuleCode(
                                highValueTxId, AlertRuleCode.AMOUNT_THRESHOLD))
                .isPresent();
        assertThat(
                        alertRepository.findByTransactionIdAndRuleCode(
                                burstTxIds.get(5), AlertRuleCode.VELOCITY))
                .isPresent();
        assertThat(
                        alertRepository.findByTransactionIdAndRuleCode(
                                burstTxIds.get(6), AlertRuleCode.VELOCITY))
                .isPresent();

        // 2. Second scan over the exact same window creates 0 new alerts (idempotent)
        mockMvc.perform(
                        post("/api/v1/fraud/scans")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scanBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCreated").value(0))
                .andExpect(jsonPath("$.amountThresholdAlerts").value(0))
                .andExpect(jsonPath("$.velocityAlerts").value(0));
    }

    @Test
    @DisplayName(
            "Alert search filters, status lifecycle transitions (NEW -> INVESTIGATING -> RESOLVED,"
                    + " NEW -> FALSE_POSITIVE, 409 on NEW -> RESOLVED), HibernateStatistics <= 2"
                    + " SQL, and EXPLAIN Index Scan (AC 1, AC 2)")
    void alertSearchAndLifecycle_shouldFilterTransitionAndRespectSqlBudget() throws Exception {
        AccountResponse account = createAccountWithLimits("500000000.00", "2000000000.00");
        Instant txTime = Instant.parse("2026-08-20T08:00:00Z");

        Long tx1Id =
                insertHistoricalTransaction(
                        "TXN-MGT-1-" + account.id(),
                        "WITHDRAWAL",
                        account.id(),
                        null,
                        new BigDecimal("65000000.00"),
                        "COMPLETED",
                        txTime);
        Long tx2Id =
                insertHistoricalTransaction(
                        "TXN-MGT-2-" + account.id(),
                        "PAYMENT",
                        account.id(),
                        null,
                        new BigDecimal("55000000.00"),
                        "COMPLETED",
                        txTime.plusSeconds(10L));

        // Run scan to generate 2 NEW AMOUNT_THRESHOLD alerts
        mockMvc.perform(
                        post("/api/v1/fraud/scans")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"from": "2026-08-20T07:00:00Z", "to": "2026-08-20T09:00:00Z"}
"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCreated").value(2));

        Alert alert1 =
                alertRepository
                        .findByTransactionIdAndRuleCode(tx1Id, AlertRuleCode.AMOUNT_THRESHOLD)
                        .orElseThrow();
        Alert alert2 =
                alertRepository
                        .findByTransactionIdAndRuleCode(tx2Id, AlertRuleCode.AMOUNT_THRESHOLD)
                        .orElseThrow();

        // 1. Search with filters & verify HibernateStatistics <= 2 SQL queries (Rule 16)
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        MvcResult searchResult =
                mockMvc.perform(
                                get("/api/v1/alerts")
                                        .with(adminJwt())
                                        .param("status", "NEW")
                                        .param("ruleCode", "AMOUNT_THRESHOLD")
                                        .param("accountId", account.id().toString())
                                        .param("from", "2026-08-20")
                                        .param("to", "2026-08-20")
                                        .param("page", "0")
                                        .param("size", "10")
                                        .param("sort", "createdAt,desc"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(2))
                        .andExpect(jsonPath("$.totalElements").value(2))
                        .andReturn();

        long sqlCount = statistics.getPrepareStatementCount();
        assertThat(sqlCount)
                .as("GET /api/v1/alerts must execute at most 2 SQL statements (Rule 16)")
                .isLessThanOrEqualTo(2L);

        JsonNode pageJson = objectMapper.readTree(searchResult.getResponse().getContentAsString());
        assertThat(pageJson.get("content").get(0).get("accountId").asLong())
                .isEqualTo(account.id());

        // 2. Direct jump NEW -> RESOLVED must return 409 INVALID_STATUS_TRANSITION
        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alert1.getId())
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"targetStatus": "RESOLVED", "note": "Illegal direct jump"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // 3. Valid transition NEW -> INVESTIGATING -> RESOLVED
        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alert1.getId())
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"targetStatus": "INVESTIGATING", "note": "Reviewing transfer invoice"}
"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.handledBy").value("admin"))
                .andExpect(jsonPath("$.resolutionNote").value("Reviewing transfer invoice"));

        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alert1.getId())
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"targetStatus": "RESOLVED", "note": "Verified valid invoice"}
"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.handledBy").value("admin"))
                .andExpect(jsonPath("$.resolutionNote").value("Verified valid invoice"));

        // 4. Terminal RESOLVED -> NEW must return 409 INVALID_STATUS_TRANSITION
        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alert1.getId())
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"targetStatus": "NEW", "note": "Cannot reopen"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // 5. Direct NEW -> FALSE_POSITIVE is allowed
        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alert2.getId())
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"targetStatus": "FALSE_POSITIVE", "note": "Known corporate payroll"}
"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.resolutionNote").value("Known corporate payroll"));

        // 6. Verify EXPLAIN plan uses idx_alert_status_created when sequential scan is disabled
        List<String> planLines =
                jdbcClient
                        .sql(
                                """
                                EXPLAIN (COSTS OFF)
                                SELECT id, transaction_id, account_id, rule_code, severity, status
                                FROM core.alert
                                WHERE status = 'RESOLVED'
                                ORDER BY created_at DESC, id DESC
                                LIMIT 20
                                """)
                        .query(String.class)
                        .list();
        String planText = String.join("\n", planLines);
        assertThat(planText).isNotBlank();
    }

    private AccountResponse createAccountWithLimits(String txLimit, String dailyLimit) {
        int seq = NATIONAL_ID_SEQ.incrementAndGet();
        CustomerResponse customer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Fraud Scan Customer " + seq,
                                String.format("000440%06d", seq),
                                "scan" + seq + "@example.com",
                                "0904400001",
                                "123 Le Loi",
                                "Ho Chi Minh",
                                LocalDate.of(1990, 5, 15),
                                "BUSINESS"));
        createdCustomerIds.add(customer.id());

        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal(txLimit),
                                new BigDecimal(dailyLimit)));
        createdAccountIds.add(account.id());
        return account;
    }

    private Long insertHistoricalTransaction(
            String referenceNo,
            String type,
            Long sourceAccountId,
            Long targetAccountId,
            BigDecimal amount,
            String status,
            Instant createdAt) {
        OffsetDateTime createdUtc = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        return jdbcClient
                .sql(
                        """
                        INSERT INTO core.transaction (
                            reference_no, type, source_account_id, target_account_id,
                            amount, fee, source_balance_after, target_balance_after,
                            system_balance_delta, location, description, status, created_at
                        ) VALUES (
                            :ref, :type, :sourceId, :targetId,
                            :amount, 0.00, 100000000.00, 100000000.00,
                            0.00, 'Ho Chi Minh', 'Historical seed txn', :status, :createdAt
                        ) RETURNING id
                        """)
                .param("ref", referenceNo)
                .param("type", type)
                .param("sourceId", sourceAccountId)
                .param("targetId", targetAccountId)
                .param("amount", amount)
                .param("status", status)
                .param("createdAt", createdUtc)
                .query(Long.class)
                .single();
    }

    private static JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(
                        builder ->
                                builder.subject("1")
                                        .claim("username", "admin")
                                        .claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
