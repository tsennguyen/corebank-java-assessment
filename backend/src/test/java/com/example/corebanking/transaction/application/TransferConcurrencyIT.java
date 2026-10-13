package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferConcurrencyIT extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @DisplayName(
            "Concurrent bidirectional transfers (A->B and B->A) execute without deadlock (NC-01)")
    void concurrentBidirectionalTransfers_shouldNotDeadlockAndPreserveTotalBalance()
            throws Exception {
        Customer customer =
                new Customer(
                        "CUS9990003",
                        "Trần Văn DeadlockFree",
                        "000999888555",
                        "tranvandeadlockfree@example.com",
                        "0900000003",
                        "Hồ Chí Minh",
                        "Hồ Chí Minh",
                        LocalDate.of(1990, 1, 1));
        Customer savedCustomer = customerRepository.save(customer);

        Account accountA =
                new Account(
                        "100999999001",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountA.credit(Money.of("10000000.00"));
        Account savedAccountA = accountRepository.save(accountA);

        Account accountB =
                new Account(
                        "100999999002",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountB.credit(Money.of("10000000.00"));
        Account savedAccountB = accountRepository.save(accountB);

        int totalThreads = 10;
        int transfersPerDirection = 5;
        BigDecimal transferAmount = new BigDecimal("100000.00"); // Free fee (< 500k)

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);

        List<Callable<Void>> tasks = new ArrayList<>();

        // 5 threads: A -> B
        for (int i = 0; i < transfersPerDirection; i++) {
            tasks.add(
                    () -> {
                        barrier.await();
                        TransferRequest request =
                                new TransferRequest(
                                        savedAccountA.getId(),
                                        savedAccountB.getId(),
                                        transferAmount,
                                        "Mobile App",
                                        "Chuyển A sang B đồng thời");
                        transactionService.transfer(request);
                        return null;
                    });
        }

        // 5 threads: B -> A
        for (int i = 0; i < transfersPerDirection; i++) {
            tasks.add(
                    () -> {
                        barrier.await();
                        TransferRequest request =
                                new TransferRequest(
                                        savedAccountB.getId(),
                                        savedAccountA.getId(),
                                        transferAmount,
                                        "Mobile App",
                                        "Chuyển B sang A đồng thời");
                        transactionService.transfer(request);
                        return null;
                    });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        Account finalAccountA = accountRepository.findById(savedAccountA.getId()).orElseThrow();
        Account finalAccountB = accountRepository.findById(savedAccountB.getId()).orElseThrow();

        // 5 * 100k out and 5 * 100k in -> net balance unchanged
        assertThat(finalAccountA.getBalance()).isEqualByComparingTo("10000000.00");
        assertThat(finalAccountB.getBalance()).isEqualByComparingTo("10000000.00");
    }

    @Test
    @DisplayName(
            "100 concurrent transfers across 10 accounts execute without deadlock and conserve"
                    + " total money (NC-01, NC-04)")
    void concurrent100TransfersAcross10Accounts_shouldNotDeadlockAndPreserveTotalMoney()
            throws Exception {
        Customer customer =
                new Customer(
                        "CUS9990100",
                        "Đoàn Trăm Luồng",
                        "000999888700",
                        "doantramluong@example.com",
                        "0900000100",
                        "Đà Nẵng",
                        "Đà Nẵng",
                        LocalDate.of(1985, 5, 20));
        Customer savedCustomer = customerRepository.save(customer);

        int numAccounts = 10;
        BigDecimal initialBalancePerAccount = new BigDecimal("200000000.00"); // 200,000,000 VND
        BigDecimal expectedTotalInitial =
                initialBalancePerAccount.multiply(
                        BigDecimal.valueOf(numAccounts)); // 2,000,000,000 VND

        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            String accNum = String.format("1009999991%02d", i);
            Account acc =
                    new Account(
                            accNum,
                            savedCustomer.getId(),
                            new BigDecimal("500000000.00"), // transactionLimit
                            new BigDecimal("10000000000.00"), // dailyLimit
                            LocalDate.now());
            acc.credit(Money.of(initialBalancePerAccount));
            accounts.add(accountRepository.save(acc));
        }

        int totalTransfers = 100;
        int concurrencyLevel = 25; // Concurrent worker threads
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CyclicBarrier barrier = new CyclicBarrier(concurrencyLevel);

        List<TransferRequest> requests = new ArrayList<>(totalTransfers);
        List<String> idempotencyKeys = new ArrayList<>(totalTransfers);

        BigDecimal[] sampleAmounts =
                new BigDecimal[] {
                    new BigDecimal("100000.00"), // 0 fee (< 500k)
                    new BigDecimal("200000.00"), // 0 fee (< 500k)
                    new BigDecimal("500000.00"), // 2000 flat fee
                    new BigDecimal("1000000.00"), // 2000 flat fee
                    new BigDecimal("2000000.00"), // 2000 flat fee
                    new BigDecimal("5000000.00"), // 2000 flat fee
                    new BigDecimal("10000000.00") // 2000 rate fee (0.02% of 10M = 2000)
                };

        for (int i = 0; i < totalTransfers; i++) {
            int srcIdx = i % numAccounts;
            int tgtIdx = (i + 1 + (i / numAccounts)) % numAccounts;
            if (srcIdx == tgtIdx) {
                tgtIdx = (tgtIdx + 1) % numAccounts;
            }
            Long srcId = accounts.get(srcIdx).getId();
            Long tgtId = accounts.get(tgtIdx).getId();
            BigDecimal amount = sampleAmounts[i % sampleAmounts.length];

            requests.add(
                    new TransferRequest(
                            srcId,
                            tgtId,
                            amount,
                            "Chi nhánh " + (i % 5),
                            "Chuyển khoản đồng thời luồng " + i));
            idempotencyKeys.add("idem-concurrency-100-" + i);
        }

        List<Callable<Void>> tasks = new ArrayList<>(totalTransfers);
        for (int i = 0; i < totalTransfers; i++) {
            final int index = i;
            tasks.add(
                    () -> {
                        if (index < concurrencyLevel) {
                            barrier.await(10, TimeUnit.SECONDS);
                        }
                        transactionService.transfer(
                                idempotencyKeys.get(index), requests.get(index));
                        return null;
                    });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        // 1. Verify every account has non-negative balance
        BigDecimal actualTotalBalance = BigDecimal.ZERO;
        for (Account acc : accounts) {
            Account refreshed = accountRepository.findById(acc.getId()).orElseThrow();
            assertThat(refreshed.getBalance())
                    .as("Tài khoản %s không được âm", refreshed.getAccountNumber())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            actualTotalBalance = actualTotalBalance.add(refreshed.getBalance());
        }

        // 2. Verify all 100 transactions succeeded and calculate total fees collected
        List<Long> accountIds = accounts.stream().map(Account::getId).toList();
        List<Transaction> completedTxns =
                transactionRepository.findAll().stream()
                        .filter(
                                t ->
                                        accountIds.contains(t.getSourceAccountId())
                                                && t.getStatus() == TransactionStatus.COMPLETED)
                        .toList();

        assertThat(completedTxns).hasSize(totalTransfers);

        BigDecimal totalFeesCollected =
                completedTxns.stream()
                        .map(Transaction::getFee)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Verify total money conservation law: Sum(Account Balances) + Sum(Fees) == Initial
        // Total
        assertThat(actualTotalBalance.add(totalFeesCollected))
                .as("Tổng số dư các tài khoản + Tổng phí thu phải bảo toàn tuyệt đối")
                .isEqualByComparingTo(expectedTotalInitial);
    }
}
