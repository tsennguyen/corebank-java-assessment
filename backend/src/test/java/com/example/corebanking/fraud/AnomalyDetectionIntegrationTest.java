package com.example.corebanking.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.application.FraudEventListener;
import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import com.example.corebanking.fraud.domain.AnomalyFinding;
import com.example.corebanking.fraud.infrastructure.AlertRepository;
import com.example.corebanking.fraud.infrastructure.SlidingWindowVelocityTracker;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack integration test verifying real-time post-commit anomaly detection on Java 21 Virtual
 * Threads ({@code AMOUNT_THRESHOLD} and {@code VELOCITY}), multi-alert creation per transaction,
 * rollback isolation, idempotent {@code ON CONFLICT DO NOTHING} persistence, and PostgreSQL B-tree
 * index plans (Story 4.3: L3-03, L3-04, L3-10, FR-22, NFR-4, ADR-012).
 */
@AutoConfigureMockMvc
class AnomalyDetectionIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger NATIONAL_ID_SEQ = new AtomicInteger(430000);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private AlertRepository alertRepository;
    @Autowired private AlertService alertService;
    @Autowired private FraudEventListener fraudEventListener;
    @Autowired private SlidingWindowVelocityTracker velocityTracker;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcClient jdbcClient;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdCustomerIds = new ArrayList<>();

    @BeforeEach
    void resetVelocityTracker() {
        velocityTracker.clearInMemoryWindows();
    }

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
            createdAccountIds.clear();
        }
        if (!createdCustomerIds.isEmpty()) {
            jdbcClient
                    .sql("DELETE FROM core.customer WHERE id IN (:ids)")
                    .param("ids", createdCustomerIds)
                    .update();
            createdCustomerIds.clear();
        }
    }

    @Test
    @DisplayName(
            "Outgoing transaction >= 50,000,000 VND commits and asynchronously creates"
                    + " AMOUNT_THRESHOLD (HIGH, NEW) Alert on a Virtual Thread (AC 1, AC 4)")
    void outgoingTransactionAboveThreshold_shouldCreateHighAlertAsynchronouslyOnVirtualThread()
            throws Exception {
        Long accountId = createFundedAccount("BUSINESS");

        double beforeCounter =
                meterRegistry
                        .counter(AlertService.ALERTS_RAISED_METRIC, "rule", "AMOUNT_THRESHOLD")
                        .count();

        Long txId = performWithdrawal(accountId, "60000000.00");

        List<Alert> alerts =
                awaitUntil(
                        () -> alertRepository.findByTransactionIdOrderByIdAsc(txId),
                        list -> !list.isEmpty(),
                        Duration.ofSeconds(5));

        assertThat(alerts).hasSize(1);
        Alert alert = alerts.getFirst();
        assertThat(alert.getTransactionId()).isEqualTo(txId);
        assertThat(alert.getAccountId()).isEqualTo(accountId);
        assertThat(alert.getRuleCode()).isEqualTo(AlertRuleCode.AMOUNT_THRESHOLD);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.NEW);
        assertThat(alert.getReason()).contains("60000000.00");

        assertThat(fraudEventListener.wasLastExecutedOnVirtualThread()).isTrue();
        assertThat(
                        meterRegistry
                                .counter(
                                        AlertService.ALERTS_RAISED_METRIC,
                                        "rule",
                                        "AMOUNT_THRESHOLD")
                                .count())
                .isEqualTo(beforeCounter + 1.0);

        // Idempotency check: raising the same (transactionId, AMOUNT_THRESHOLD) a second time does
        // nothing (ON CONFLICT DO NOTHING)
        boolean insertedAgain =
                alertService.raise(
                        txId,
                        accountId,
                        new AnomalyFinding(
                                AlertRuleCode.AMOUNT_THRESHOLD,
                                AlertSeverity.HIGH,
                                "Duplicate test"));
        assertThat(insertedAgain).isFalse();
        assertThat(alertRepository.findByTransactionIdOrderByIdAsc(txId)).hasSize(1);
    }

    @Test
    @DisplayName(
            "6th outgoing transaction within 60s that also exceeds 50M VND creates both"
                    + " AMOUNT_THRESHOLD and VELOCITY alerts (AC 2, AC 4)")
    void sixOutgoingTransactionsWithin60Seconds_when6thExceedsThreshold_shouldCreateTwoAlerts()
            throws Exception {
        Long accountId = createFundedAccount("BUSINESS");

        // 5 small withdrawals (1,000,000 VND each) -> no alert
        for (int i = 1; i <= 5; i++) {
            Long txId = performWithdrawal(accountId, "1000000.00");
            assertThat(alertRepository.findByTransactionIdOrderByIdAsc(txId)).isEmpty();
        }

        // 6th withdrawal is 55,000,000 VND -> triggers BOTH AMOUNT_THRESHOLD (HIGH) and VELOCITY
        // (MEDIUM)
        Long tx6Id = performWithdrawal(accountId, "55000000.00");

        List<Alert> alerts =
                awaitUntil(
                        () -> alertRepository.findByTransactionIdOrderByIdAsc(tx6Id),
                        list -> list.size() == 2,
                        Duration.ofSeconds(5));

        assertThat(alerts)
                .extracting(Alert::getRuleCode)
                .containsExactlyInAnyOrder(AlertRuleCode.AMOUNT_THRESHOLD, AlertRuleCode.VELOCITY);
        assertThat(alerts)
                .filteredOn(a -> a.getRuleCode() == AlertRuleCode.VELOCITY)
                .first()
                .satisfies(
                        a -> {
                            assertThat(a.getSeverity()).isEqualTo(AlertSeverity.MEDIUM);
                            assertThat(a.getStatus()).isEqualTo(AlertStatus.NEW);
                            assertThat(a.getAccountId()).isEqualTo(accountId);
                        });
    }

    @Test
    @DisplayName(
            "Incoming DEPOSIT >= 50M VND and rolled-back outgoing transaction do not generate any"
                    + " Alert (AC 3)")
    void depositAndRolledBackOutgoingTransaction_shouldNotGenerateAlerts() throws Exception {
        Long accountId = createFundedAccount("INDIVIDUAL");

        // 1. Incoming DEPOSIT of 80,000,000 VND -> 201 Created, but not an outgoing transaction
        MvcResult depositResult =
                mockMvc.perform(
                                post("/api/v1/transactions/deposits")
                                        .with(
                                                jwt().authorities(
                                                                new SimpleGrantedAuthority(
                                                                        "ROLE_ADMIN")))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"accountId\":"
                                                        + accountId
                                                        + ",\"amount\":80000000.00,\"location\":\"Hà"
                                                        + " Nội\",\"description\":\"Large"
                                                        + " deposit\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode depositJson =
                objectMapper.readTree(depositResult.getResponse().getContentAsString());
        Long depositTxId = depositJson.get("id").asLong();

        // 2. Outgoing withdrawal of 150,000,000 VND on INDIVIDUAL account -> rolled back with 422
        // LIMIT_EXCEEDED
        mockMvc.perform(
                        post("/api/v1/transactions/withdrawals")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"accountId\":"
                                                + accountId
                                                + ",\"amount\":150000000.00,\"location\":\"Hà"
                                                + " Nội\",\"description\":\"Exceeds limit\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));

        Thread.sleep(150);
        assertThat(alertRepository.findByTransactionIdOrderByIdAsc(depositTxId)).isEmpty();
        Integer alertCountForAccount =
                jdbcClient
                        .sql("SELECT COUNT(*) FROM core.alert WHERE account_id = :accountId")
                        .param("accountId", accountId)
                        .query(Integer.class)
                        .single();
        assertThat(alertCountForAccount).isZero();
    }

    @Test
    @DisplayName(
            "PostgreSQL EXPLAIN (ANALYZE, BUFFERS) uses Index Scan on core.alert composite indexes"
                    + " (Rule 6)")
    void explainAnalyze_onAlertStatusAndAccountQueries_shouldUseIndexScan() throws Exception {
        Long accountId = createFundedAccount("BUSINESS");
        Long txId = performWithdrawal(accountId, "65000000.00");

        awaitUntil(
                () -> alertRepository.findByTransactionIdOrderByIdAsc(txId),
                list -> !list.isEmpty(),
                Duration.ofSeconds(5));

        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> planLines =
                    jdbcClient
                            .sql(
                                    """
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, transaction_id, account_id, rule_code, severity, status, created_at
FROM core.alert
WHERE status = 'NEW'
ORDER BY created_at DESC, id DESC
LIMIT 20
""")
                            .query(String.class)
                            .list();
            String fullPlan = String.join("\n", planLines);
            assertThat(fullPlan).contains("Index Scan").contains("idx_alert_status_created");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }
    }

    private Long performWithdrawal(Long accountId, String amount) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/transactions/withdrawals")
                                        .with(
                                                jwt().authorities(
                                                                new SimpleGrantedAuthority(
                                                                        "ROLE_ADMIN")))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"accountId\":"
                                                        + accountId
                                                        + ",\"amount\":"
                                                        + amount
                                                        + ",\"location\":\"Hà"
                                                        + " Nội\",\"description\":\"Test"
                                                        + " withdrawal\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private Long createFundedAccount(String customerTypeCode) {
        int seq = NATIONAL_ID_SEQ.incrementAndGet();
        String nationalId = String.format("000999%06d", seq);
        CustomerResponse customer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Khách Hàng Mẫu Fraud " + seq,
                                nationalId,
                                "fraud." + seq + "@example.com",
                                "0900430001",
                                "Số 10 Đường Mẫu, Hà Nội",
                                "Hà Nội",
                                LocalDate.of(1990, 1, 1),
                                customerTypeCode));
        createdCustomerIds.add(customer.id());

        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal("500000000.00"),
                                new BigDecimal("2000000000.00")));
        createdAccountIds.add(account.id());

        transactionService.deposit(
                new DepositRequest(
                        account.id(),
                        new BigDecimal("1000000000.00"),
                        "Hà Nội",
                        "Initial funding for fraud test"));
        return account.id();
    }

    private static <T> T awaitUntil(
            Supplier<T> supplier, java.util.function.Predicate<T> condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T lastValue = supplier.get();
        while (!condition.test(lastValue) && System.nanoTime() < deadline) {
            Thread.sleep(25);
            lastValue = supplier.get();
        }
        return lastValue;
    }
}
