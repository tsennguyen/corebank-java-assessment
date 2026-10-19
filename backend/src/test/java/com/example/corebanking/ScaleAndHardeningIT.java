package com.example.corebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.common.audit.AuditLog;
import com.example.corebanking.common.audit.AuditLogRepository;
import com.example.corebanking.common.scheduler.SchedulerLockService;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.recurring.api.dto.CreateRecurringTransactionRequest;
import com.example.corebanking.recurring.application.RecurringExecutor;
import com.example.corebanking.recurring.application.RecurringTransactionJob;
import com.example.corebanking.recurring.application.RecurringTransactionService;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringProperties;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack integration test for Epic 6 Scale & Hardening (Stories 6.1, 6.2, 6.3) against
 * PostgreSQL 17 Testcontainers.
 */
@AutoConfigureMockMvc
class ScaleAndHardeningIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private RecurringTransactionService recurringService;
    @Autowired private RecurringExecutor recurringExecutor;
    @Autowired private SchedulerLockService schedulerLockService;
    @Autowired private AlertService alertService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName(
            "Story 6.1: Keyset pagination scrolls deterministically with nextCursor, rejects"
                    + " tampered cursor with 400 INVALID_CURSOR, and uses Index Scan without Sort")
    void keysetPagination_shouldScrollPagesAndUseIndexScanWithoutSort() throws Exception {
        CustomerResponse customer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Nguyễn Keyset",
                                "000900601001",
                                "keyset601@example.com",
                                "0901234567",
                                "12 Nguyễn Huệ",
                                "TP. Hồ Chí Minh",
                                LocalDate.of(1992, 5, 10),
                                "INDIVIDUAL"));
        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00")));

        for (int i = 1; i <= 5; i++) {
            transactionService.deposit(
                    new DepositRequest(
                            account.id(),
                            new BigDecimal(i * 100000),
                            "TP. Hồ Chí Minh",
                            "Keyset deposit #" + i));
        }

        Statistics stats =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Page 1 (size=2) without cursor
        MvcResult page1Result =
                mockMvc.perform(
                                get("/api/v1/accounts/{id}/transactions/cursor", account.id())
                                        .param("size", "2")
                                        .with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content", hasSize(2)))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn();

        assertThat(stats.getPrepareStatementCount())
                .as(
                        "GET /api/v1/accounts/{id}/transactions/cursor must execute at most 2 SQL"
                                + " queries (existsById + keyset seek without COUNT)")
                .isLessThanOrEqualTo(2L);

        JsonNode page1Json = objectMapper.readTree(page1Result.getResponse().getContentAsString());
        String cursor1 = page1Json.get("nextCursor").asText();
        List<Long> collectedIds = new ArrayList<>();
        collectedIds.add(page1Json.get("content").get(0).get("id").asLong());
        collectedIds.add(page1Json.get("content").get(1).get("id").asLong());

        // Page 2 (size=2) using ?cursor= query parameter on /api/v1/accounts/{id}/transactions
        MvcResult page2Result =
                mockMvc.perform(
                                get("/api/v1/accounts/{id}/transactions", account.id())
                                        .param("cursor", cursor1)
                                        .param("size", "2")
                                        .with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content", hasSize(2)))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn();

        JsonNode page2Json = objectMapper.readTree(page2Result.getResponse().getContentAsString());
        String cursor2 = page2Json.get("nextCursor").asText();
        collectedIds.add(page2Json.get("content").get(0).get("id").asLong());
        collectedIds.add(page2Json.get("content").get(1).get("id").asLong());

        // Page 3 (size=2) -> final 1 item, nextCursor == null, hasNext == false
        MvcResult page3Result =
                mockMvc.perform(
                                get("/api/v1/accounts/{id}/transactions/cursor", account.id())
                                        .param("cursor", cursor2)
                                        .param("size", "2")
                                        .with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content", hasSize(1)))
                        .andExpect(jsonPath("$.hasNext").value(false))
                        .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                        .andReturn();

        JsonNode page3Json = objectMapper.readTree(page3Result.getResponse().getContentAsString());
        collectedIds.add(page3Json.get("content").get(0).get("id").asLong());

        assertThat(collectedIds).hasSize(5).doesNotHaveDuplicates();

        // Tampered cursor -> 400 INVALID_CURSOR ProblemDetail
        mockMvc.perform(
                        get("/api/v1/accounts/{id}/transactions/cursor", account.id())
                                .param("cursor", "tampered-invalid-cursor!!!")
                                .param("size", "2")
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));

        // Verify EXPLAIN plan on keyset index uses Index Scan and no Sort node
        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> planLines =
                    jdbcClient
                            .sql(
                                    """
                                    EXPLAIN (COSTS OFF)
                                    SELECT id, reference_no, created_at
                                    FROM core.transaction
                                    WHERE target_account_id = :accountId
                                      AND (created_at, id) < (CURRENT_TIMESTAMP, 999999999)
                                    ORDER BY created_at DESC, id DESC
                                    LIMIT 50
                                    """)
                            .param("accountId", account.id())
                            .query(String.class)
                            .list();
            String fullPlan = String.join("\n", planLines);
            assertThat(fullPlan).containsIgnoringCase("Index").doesNotContainIgnoringCase("Sort");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }
    }

    @Test
    @DisplayName(
            "Story 6.2: Two cluster RecurringTransactionJob instances competing on core.shedlock"
                    + " execute due batch only once")
    void shedLock_twoClusterInstances_shouldExecuteOnlyOncePerWindow() {
        CustomerResponse customer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Trần ShedLock",
                                "000900602002",
                                "shedlock602@example.com",
                                "0912345678",
                                "34 Tràng Tiền",
                                "Hà Nội",
                                LocalDate.of(1990, 3, 20),
                                "INDIVIDUAL"));
        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00")));

        var recurring =
                recurringService.createRecurringTransaction(
                        new CreateRecurringTransactionRequest(
                                RecurringTransactionType.DEPOSIT,
                                null,
                                account.id(),
                                new BigDecimal("500000.00"),
                                RecurringFrequency.DAILY,
                                LocalDate.now().plusDays(1),
                                null,
                                "Hà Nội",
                                "ShedLock recurring deposit"));

        // Force instruction to be due now
        jdbcClient
                .sql(
                        "UPDATE core.recurring_transaction SET next_run_at = :past WHERE id ="
                                + " :id")
                .param("past", java.time.OffsetDateTime.now().minusMinutes(5))
                .param("id", recurring.id())
                .update();

        // Clear any prior lock row in core.shedlock
        jdbcClient.sql("DELETE FROM core.shedlock WHERE name = 'recurringTransactionJob'").update();

        RecurringProperties props = new RecurringProperties();
        props.setSchedulerEnabled(true);
        props.setBatchSize(50);

        RecurringTransactionJob node1 =
                new RecurringTransactionJob(
                        recurringExecutor, props, schedulerLockService, "cluster-node-1");
        RecurringTransactionJob node2 =
                new RecurringTransactionJob(
                        recurringExecutor, props, schedulerLockService, "cluster-node-2");

        int processedByNode1 = node1.runClusterLockedDueTransactions();
        int processedByNode2 = node2.runClusterLockedDueTransactions();

        assertThat(processedByNode1).isGreaterThanOrEqualTo(1);
        assertThat(processedByNode2).isZero();

        String lockedBy =
                jdbcClient
                        .sql(
                                "SELECT locked_by FROM core.shedlock WHERE name ="
                                        + " 'recurringTransactionJob'")
                        .query(String.class)
                        .single();
        assertThat(lockedBy).isEqualTo("cluster-node-1");
    }

    @Test
    @DisplayName(
            "Story 6.3: Sensitive operations write masked audit_log in the same transaction,"
                    + " enforce append-only trigger, and restrict GET /api/v1/admin/audit-logs to"
                    + " ADMIN")
    void auditLog_shouldRecordSensitiveOperationsWithMaskingAndEnforceAdminAccess()
            throws Exception {
        String rawNationalId = "000900603912";
        CustomerResponse customer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Lê Audit",
                                rawNationalId,
                                "audit603@example.com",
                                "0988776655",
                                "56 Bạch Đằng",
                                "Đà Nẵng",
                                LocalDate.of(1988, 11, 2),
                                "INDIVIDUAL"));
        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal("20000000.00"),
                                new BigDecimal("100000000.00")));
        String rawAccountNumber = account.accountNumber();

        // 1. Update account limits as ADMIN -> records ACCOUNT_LIMITS_UPDATED
        mockMvc.perform(
                        patch("/api/v1/accounts/{id}", account.id())
                                .with(user("admin.auditor").roles("ADMIN"))
                                .header("X-Request-Id", "req-audit-limits-1")
                                .header("X-Forwarded-For", "10.20.30.40")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "transactionLimit": 30000000.00,
                                          "dailyLimit": 120000000.00,
                                          "version": 0
                                        }
                                        """))
                .andExpect(status().isOk());

        // 2. Lock and unlock account as ADMIN -> records ACCOUNT_STATUS_CHANGED
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", account.id())
                                .with(user("admin.auditor").roles("ADMIN"))
                                .header("X-Request-Id", "req-audit-status-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "LOCKED",
                                          "reason": "Kiểm tra bảo mật định kỳ"
                                        }
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", account.id())
                                .with(user("admin.auditor").roles("ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "ACTIVE",
                                          "reason": "Mở khóa sau kiểm tra"
                                        }
                                        """))
                .andExpect(status().isOk());

        // 3. Deposit and reverse transaction as ADMIN -> records TRANSACTION_REVERSED
        TransactionResponse deposit =
                transactionService.deposit(
                        new DepositRequest(
                                account.id(),
                                new BigDecimal("2500000.00"),
                                "Đà Nẵng",
                                "Deposit to reverse"));

        mockMvc.perform(
                        delete("/api/v1/transactions/{id}", deposit.id())
                                .with(user("admin.auditor").roles("ADMIN"))
                                .header("X-Request-Id", "req-audit-reverse-1"))
                .andExpect(status().isOk());

        // 4. Raise an alert and transition status as ADMIN -> records ALERT_STATUS_CHANGED
        alertService.raise(
                deposit.id(),
                account.id(),
                new AnomalyFinding(
                        AlertRuleCode.AMOUNT_THRESHOLD,
                        AlertSeverity.HIGH,
                        "High value test alert"));
        Long alertId =
                jdbcClient
                        .sql("SELECT id FROM core.alert WHERE transaction_id = :txId")
                        .param("txId", deposit.id())
                        .query(Long.class)
                        .single();

        mockMvc.perform(
                        post("/api/v1/alerts/{id}/status", alertId)
                                .with(user("admin.auditor").roles("ADMIN"))
                                .header("X-Request-Id", "req-audit-alert-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetStatus": "INVESTIGATING",
                                          "note": "Đang xác minh giao dịch"
                                        }
                                        """))
                .andExpect(status().isOk());

        // 5. Verify CUSTOMER receives 403 Forbidden on GET /api/v1/admin/audit-logs
        mockMvc.perform(get("/api/v1/admin/audit-logs").with(user("cust.an").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 6. Verify ADMIN can query audit logs filtered by actor and sensitive fields are masked
        Statistics stats =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        MvcResult auditResult =
                mockMvc.perform(
                                get("/api/v1/admin/audit-logs")
                                        .param("actor", "admin.auditor")
                                        .param("size", "50")
                                        .with(user("admin.auditor").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(5))))
                        .andReturn();

        assertThat(stats.getPrepareStatementCount())
                .as(
                        "GET /api/v1/admin/audit-logs must execute at most 2 SQL statements (SELECT"
                                + " + COUNT, no N+1)")
                .isLessThanOrEqualTo(2L);

        String body = auditResult.getResponse().getContentAsString();
        assertThat(body)
                .contains("ACCOUNT_LIMITS_UPDATED")
                .contains("ACCOUNT_STATUS_CHANGED")
                .contains("TRANSACTION_REVERSED")
                .contains("ALERT_STATUS_CHANGED")
                .contains("*********912")
                .doesNotContain(rawNationalId)
                .doesNotContain(rawAccountNumber);

        // 7. Verify core.audit_log is strictly append-only (UPDATE and DELETE are blocked by
        // PostgreSQL trigger) and uses Index Scan on idx_audit_log_actor_created
        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> auditPlanLines =
                    jdbcClient
                            .sql(
                                    """
                                    EXPLAIN (COSTS OFF)
                                    SELECT id, actor, action, entity_type, entity_id, created_at
                                    FROM core.audit_log
                                    WHERE actor = 'admin.auditor'
                                    ORDER BY created_at DESC, id DESC
                                    LIMIT 50
                                    """)
                            .query(String.class)
                            .list();
            String auditPlan = String.join("\n", auditPlanLines);
            assertThat(auditPlan).containsIgnoringCase("Index").doesNotContainIgnoringCase("Sort");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }

        List<AuditLog> allLogs = auditLogRepository.findAll();
        assertThat(allLogs).isNotEmpty();
        Long sampleAuditId = allLogs.get(0).getId();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql("DELETE FROM core.audit_log WHERE id = :id")
                                        .param("id", sampleAuditId)
                                        .update())
                .hasMessageContaining("append-only");
    }
}
