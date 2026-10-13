package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferConcurrencyIT extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;

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
}
