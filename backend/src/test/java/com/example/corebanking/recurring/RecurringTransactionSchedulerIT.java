package com.example.corebanking.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.recurring.application.RecurringTransactionJob;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Integration tests on PostgreSQL 17 verifying scheduled recurring transaction execution,
 * transaction isolation on business rule failures, automatic pause after 3 consecutive failures,
 * and concurrent worker execution with {@code FOR UPDATE SKIP LOCKED} (Story 3.6: L2-07, FR-17,
 * ADR-011).
 */
class RecurringTransactionSchedulerIT extends IntegrationTestBase {

    @Autowired private RecurringTransactionJob recurringTransactionJob;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private Clock clock;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private final List<Long> createdRecurringIds = new ArrayList<>();
    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdCustomerIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestData() {
        if (!createdRecurringIds.isEmpty()) {
            jdbcClient
                    .sql("DELETE FROM core.transaction WHERE recurring_id IN (:ids)")
                    .param("ids", createdRecurringIds)
                    .update();
            jdbcClient
                    .sql("DELETE FROM core.recurring_transaction WHERE id IN (:ids)")
                    .param("ids", createdRecurringIds)
                    .update();
            createdRecurringIds.clear();
        }
        if (!createdAccountIds.isEmpty()) {
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
            "AC 1: With 3 due ACTIVE instructions and 1 future instruction, executes exactly 3"
                    + " with REC-{id}-{yyyyMMdd} referenceNo, tiered fees, and schedule"
                    + " advancement/completion")
    void runDueTransactions_with3DueAnd1Future_shouldExecuteExactly3WithRecReferenceNo() {
        Customer customer = createCustomer("CUS9306001", "000306000001", "an.rec36@example.com");
        Account accountA =
                createAccount(
                        "100930600015",
                        customer.getId(),
                        new BigDecimal("20000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"));
        Account accountB =
                createAccount(
                        "100930600023",
                        customer.getId(),
                        new BigDecimal("5000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"));

        LocalDate today = LocalDate.now(clock);
        String periodSuffix = today.format(DateTimeFormatter.BASIC_ISO_DATE);

        // 1. Due DEPOSIT (completes after today because endDate == today)
        RecurringTransaction dueDeposit =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.DEPOSIT,
                                null,
                                accountB.getId(),
                                new BigDecimal("1000000.00"),
                                RecurringFrequency.DAILY,
                                today,
                                today,
                                "HQ",
                                "Scheduled deposit",
                                "admin",
                                clock));

        // 2. Due PAYMENT (advances 1 week)
        RecurringTransaction duePayment =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.PAYMENT,
                                accountA.getId(),
                                null,
                                new BigDecimal("500000.00"),
                                RecurringFrequency.WEEKLY,
                                today,
                                today.plusMonths(1),
                                "Online",
                                "Scheduled utility payment",
                                "admin",
                                clock));

        // 3. Due TRANSFER >= 500,000 VND (incurs 2,000 VND tiered transfer fee)
        RecurringTransaction dueTransfer =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.TRANSFER,
                                accountA.getId(),
                                accountB.getId(),
                                new BigDecimal("2000000.00"),
                                RecurringFrequency.MONTHLY,
                                today,
                                null,
                                "System",
                                "Scheduled monthly transfer",
                                "admin",
                                clock));

        // 4. Future DEPOSIT (startDate = tomorrow -> should NOT be executed)
        RecurringTransaction futureDeposit =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.DEPOSIT,
                                null,
                                accountB.getId(),
                                new BigDecimal("9000000.00"),
                                RecurringFrequency.DAILY,
                                today.plusDays(1),
                                null,
                                "HQ",
                                "Future deposit",
                                "admin",
                                clock));

        int processed = recurringTransactionJob.runDueTransactions();
        assertThat(processed).isEqualTo(3);

        // Verify deterministic reference numbers REC-{id}-{yyyyMMdd} and recurringId links
        Transaction depositTx =
                transactionRepository
                        .findByReferenceNo("REC-" + dueDeposit.getId() + "-" + periodSuffix)
                        .orElseThrow();
        assertThat(depositTx.getRecurringId()).isEqualTo(dueDeposit.getId());
        assertThat(depositTx.getAmount()).isEqualByComparingTo("1000000.00");

        Transaction paymentTx =
                transactionRepository
                        .findByReferenceNo("REC-" + duePayment.getId() + "-" + periodSuffix)
                        .orElseThrow();
        assertThat(paymentTx.getRecurringId()).isEqualTo(duePayment.getId());
        assertThat(paymentTx.getAmount()).isEqualByComparingTo("500000.00");

        Transaction transferTx =
                transactionRepository
                        .findByReferenceNo("REC-" + dueTransfer.getId() + "-" + periodSuffix)
                        .orElseThrow();
        assertThat(transferTx.getRecurringId()).isEqualTo(dueTransfer.getId());
        assertThat(transferTx.getAmount()).isEqualByComparingTo("2000000.00");
        assertThat(transferTx.getFee()).isEqualByComparingTo("2000.00");

        // Verify account balances:
        // accountA: 20,000,000 - 500,000 (payment) - 2,002,000 (transfer + fee) = 17,498,000
        // accountB: 5,000,000 + 1,000,000 (deposit) + 2,000,000 (transfer) = 8,000,000
        Account updatedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account updatedB = accountRepository.findById(accountB.getId()).orElseThrow();
        assertThat(updatedA.getBalance()).isEqualByComparingTo("17498000.00");
        assertThat(updatedB.getBalance()).isEqualByComparingTo("8000000.00");

        // Verify recurring instruction states
        RecurringTransaction reloadedDeposit =
                recurringRepository.findById(dueDeposit.getId()).orElseThrow();
        assertThat(reloadedDeposit.getStatus()).isEqualTo(RecurringStatus.COMPLETED);
        assertThat(reloadedDeposit.getLastRunAt()).isNotNull();
        assertThat(reloadedDeposit.getFailureCount()).isZero();

        RecurringTransaction reloadedPayment =
                recurringRepository.findById(duePayment.getId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(reloadedPayment.getNextRunAt().atZone(clock.getZone()).toLocalDate())
                .isEqualTo(today.plusWeeks(1));

        RecurringTransaction reloadedFuture =
                recurringRepository.findById(futureDeposit.getId()).orElseThrow();
        assertThat(reloadedFuture.getLastRunAt()).isNull();

        // Verify empty-queue poll executes exactly 1 SQL statement (SELECT ... FOR UPDATE SKIP
        // LOCKED)
        var statistics =
                entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(recurringTransactionJob.runDueTransactions()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);

        // Verify PostgreSQL planner uses LockRows + Index Scan on partial index idx_rec_due
        jdbcClient.sql("SET enable_seqscan = off").update();
        try {
            List<String> explainLines =
                    jdbcClient
                            .sql(
                                    "EXPLAIN SELECT * FROM core.recurring_transaction"
                                            + " WHERE status = 'ACTIVE' AND next_run_at <="
                                            + " CURRENT_TIMESTAMP ORDER BY next_run_at ASC, id ASC"
                                            + " LIMIT 1 FOR UPDATE SKIP LOCKED")
                            .query(String.class)
                            .list();
            String planText = String.join("\n", explainLines);
            assertThat(planText).contains("idx_rec_due").contains("LockRows");
        } finally {
            jdbcClient.sql("RESET enable_seqscan").update();
        }
    }

    @Test
    @DisplayName(
            "AC 2: When one due instruction fails with INSUFFICIENT_BALANCE, other instructions"
                    + " commit and the failing one records failureCount/lastError and pauses at 3"
                    + " failures")
    void runDueTransactions_whenOneFailsInBatch_shouldCommitOthersAndPauseFailedOneAtMaxFailures() {
        Customer customer = createCustomer("CUS9306002", "000306000002", "binh.rec36@example.com");
        Account lowBalanceAccount =
                createAccount(
                        "100930600031",
                        customer.getId(),
                        new BigDecimal("100000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"));
        Account healthyAccount =
                createAccount(
                        "100930600049",
                        customer.getId(),
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"));

        LocalDate today = LocalDate.now(clock);

        RecurringTransaction validBefore =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.DEPOSIT,
                                null,
                                healthyAccount.getId(),
                                new BigDecimal("500000.00"),
                                RecurringFrequency.DAILY,
                                today,
                                null,
                                "HQ",
                                "Valid deposit 1",
                                "admin",
                                clock));

        RecurringTransaction failingPayment =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.PAYMENT,
                                lowBalanceAccount.getId(),
                                null,
                                new BigDecimal("5000000.00"),
                                RecurringFrequency.DAILY,
                                today,
                                null,
                                "Online",
                                "Over-balance payment",
                                "admin",
                                clock));

        RecurringTransaction validAfter =
                saveRecurring(
                        RecurringTransaction.create(
                                RecurringTransactionType.PAYMENT,
                                healthyAccount.getId(),
                                null,
                                new BigDecimal("300000.00"),
                                RecurringFrequency.DAILY,
                                today,
                                null,
                                "Online",
                                "Valid payment 2",
                                "admin",
                                clock));

        int processed = recurringTransactionJob.runDueTransactions();
        assertThat(processed).isEqualTo(3);

        // Healthy account processed both valid instructions (+500,000 - 300,000 = 10,200,000)
        Account reloadedHealthy = accountRepository.findById(healthyAccount.getId()).orElseThrow();
        assertThat(reloadedHealthy.getBalance()).isEqualByComparingTo("10200000.00");

        // Low balance account was untouched (100,000)
        Account reloadedLow = accountRepository.findById(lowBalanceAccount.getId()).orElseThrow();
        assertThat(reloadedLow.getBalance()).isEqualByComparingTo("100000.00");

        // Failing instruction has failureCount = 1, lastError recorded, and nextRunAt advanced
        RecurringTransaction afterFail1 =
                recurringRepository.findById(failingPayment.getId()).orElseThrow();
        assertThat(afterFail1.getFailureCount()).isEqualTo(1);
        assertThat(afterFail1.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(afterFail1.getLastError()).contains("INSUFFICIENT_BALANCE");
        assertThat(afterFail1.getNextRunAt().atZone(clock.getZone()).toLocalDate())
                .isEqualTo(today.plusDays(1));
        assertThat(
                        recurringRepository
                                .findById(validBefore.getId())
                                .orElseThrow()
                                .getFailureCount())
                .isZero();
        assertThat(recurringRepository.findById(validAfter.getId()).orElseThrow().getFailureCount())
                .isZero();

        // Simulate 2nd and 3rd consecutive due periods failing -> transitions to PAUSED
        forceNextRunAtDueNow(failingPayment.getId(), clock.instant().minusSeconds(60));
        recurringTransactionJob.runDueTransactions();
        RecurringTransaction afterFail2 =
                recurringRepository.findById(failingPayment.getId()).orElseThrow();
        assertThat(afterFail2.getFailureCount()).isEqualTo(2);
        assertThat(afterFail2.getStatus()).isEqualTo(RecurringStatus.ACTIVE);

        forceNextRunAtDueNow(failingPayment.getId(), clock.instant().minusSeconds(60));
        recurringTransactionJob.runDueTransactions();
        RecurringTransaction afterFail3 =
                recurringRepository.findById(failingPayment.getId()).orElseThrow();
        assertThat(afterFail3.getFailureCount()).isEqualTo(3);
        assertThat(afterFail3.getStatus()).isEqualTo(RecurringStatus.PAUSED);
        assertThat(afterFail3.getLastError()).contains("INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName(
            "AC 3: Two concurrent worker threads executing 10 due instructions with FOR UPDATE"
                    + " SKIP LOCKED process each instruction exactly once without duplicate"
                    + " transactions")
    void runDueTransactions_concurrentWorkers_shouldExecuteEachDueInstructionExactlyOnce()
            throws Exception {
        Customer customer = createCustomer("CUS9306003", "000306000003", "cuong.rec36@example.com");
        Account targetAccount =
                createAccount(
                        "100930600056",
                        customer.getId(),
                        new BigDecimal("1000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"));

        LocalDate today = LocalDate.now(clock);
        String periodSuffix = today.format(DateTimeFormatter.BASIC_ISO_DATE);

        List<Long> batchIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RecurringTransaction rec =
                    saveRecurring(
                            RecurringTransaction.create(
                                    RecurringTransactionType.DEPOSIT,
                                    null,
                                    targetAccount.getId(),
                                    new BigDecimal("100000.00"),
                                    RecurringFrequency.DAILY,
                                    today,
                                    null,
                                    "WorkerPool",
                                    "Concurrent item " + i,
                                    "admin",
                                    clock));
            batchIds.add(rec.getId());
        }

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> workerTask =
                    () -> {
                        startBarrier.await(5, TimeUnit.SECONDS);
                        return recurringTransactionJob.runDueTransactions();
                    };
            Future<Integer> worker1 = executor.submit(workerTask);
            Future<Integer> worker2 = executor.submit(workerTask);

            int totalProcessed =
                    worker1.get(15, TimeUnit.SECONDS) + worker2.get(15, TimeUnit.SECONDS);
            assertThat(totalProcessed).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }

        // Verify target account received exactly 10 * 100,000 = +1,000,000 (total 2,000,000)
        Account reloadedTarget = accountRepository.findById(targetAccount.getId()).orElseThrow();
        assertThat(reloadedTarget.getBalance()).isEqualByComparingTo("2000000.00");

        // Verify all 10 REC-{id}-{yyyyMMdd} transactions exist with distinct recurringIds
        Set<String> expectedRefs =
                batchIds.stream()
                        .map(id -> "REC-" + id + "-" + periodSuffix)
                        .collect(Collectors.toSet());
        for (String ref : expectedRefs) {
            assertThat(transactionRepository.findByReferenceNo(ref)).isPresent();
        }
    }

    private void forceNextRunAtDueNow(Long recurringId, Instant nextRunAt) {
        jdbcClient
                .sql(
                        "UPDATE core.recurring_transaction SET next_run_at = :nextRunAt WHERE id ="
                                + " :id")
                .param("nextRunAt", java.sql.Timestamp.from(nextRunAt))
                .param("id", recurringId)
                .update();
    }

    private Customer createCustomer(String customerCode, String nationalId, String email) {
        Customer saved =
                customerRepository.saveAndFlush(
                        new Customer(
                                customerCode,
                                "Khách Hàng Định Kỳ",
                                nationalId,
                                email,
                                "0900003601",
                                "Số 1 Đường Mẫu",
                                "Hà Nội",
                                LocalDate.of(1992, 6, 15)));
        createdCustomerIds.add(saved.getId());
        return saved;
    }

    private Account createAccount(
            String accountNumber,
            Long customerId,
            BigDecimal balance,
            BigDecimal singleLimit,
            BigDecimal dailyLimit) {
        Account account =
                new Account(
                        accountNumber, customerId, singleLimit, dailyLimit, LocalDate.now(clock));
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            account.credit(com.example.corebanking.common.domain.Money.of(balance));
        }
        Account saved = accountRepository.saveAndFlush(account);
        createdAccountIds.add(saved.getId());
        return saved;
    }

    private RecurringTransaction saveRecurring(RecurringTransaction recurring) {
        RecurringTransaction saved = recurringRepository.saveAndFlush(recurring);
        createdRecurringIds.add(saved.getId());
        return saved;
    }
}
