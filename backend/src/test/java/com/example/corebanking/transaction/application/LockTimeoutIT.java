package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LockTimeoutIT extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Transaction attempting to lock an already locked account times out after ~3s")
    void concurrentLockAttempt_shouldTimeoutAfterConfiguredDuration() throws Exception {
        Customer customer =
                new Customer(
                        "CUS9990002",
                        "Trần Văn Lock",
                        "000999888666",
                        "tranvanlock@example.com",
                        "0900000002",
                        "Hà Nội",
                        "Hà Nội",
                        LocalDate.of(1990, 1, 1));
        Customer savedCustomer = customerRepository.save(customer);

        Account account =
                new Account(
                        "100888888888",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        Account savedAccount = accountRepository.save(account);

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Thread 1: Acquires pessimistic write lock on target account and holds it
        Future<?> holderFuture =
                executor.submit(
                        () -> {
                            txTemplate.execute(
                                    status -> {
                                        accountRepository.findByIdForUpdate(savedAccount.getId());
                                        lockAcquiredLatch.countDown();
                                        try {
                                            releaseLockLatch.await(6, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return null;
                                    });
                        });

        // Wait until Thread 1 has acquired the lock
        assertThat(lockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        long startTime = System.currentTimeMillis();

        // Thread 2: attempts to deposit which requests pessimistic lock on the same account
        Future<?> waiterFuture =
                executor.submit(
                        () -> {
                            DepositRequest request =
                                    new DepositRequest(
                                            savedAccount.getId(),
                                            new BigDecimal("50000.00"),
                                            "Hà Nội",
                                            "Nạp tiền thử lock timeout");
                            transactionService.deposit(request);
                        });

        assertThatThrownBy(waiterFuture::get)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class);

        long elapsedTime = System.currentTimeMillis() - startTime;
        // Hikari connection-init-sql configures lock_timeout = '3s'. Total wait time is ~3s
        assertThat(elapsedTime).isBetween(2500L, 5500L);

        releaseLockLatch.countDown();
        holderFuture.get();
        executor.shutdown();
    }
}
