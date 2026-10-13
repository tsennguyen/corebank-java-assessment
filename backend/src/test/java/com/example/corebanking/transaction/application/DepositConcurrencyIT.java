package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DepositConcurrencyIT extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;

    @Test
    @DisplayName(
            "10 concurrent threads depositing 100,000 VND into same account preserves total"
                    + " balance")
    void concurrentDeposits_shouldPreserveTotalBalance() throws Exception {
        Customer customer =
                new Customer(
                        "CUS9990001",
                        "Trần Văn Test",
                        "000999888777",
                        "tranvantest@example.com",
                        "0900000001",
                        "Hà Nội",
                        "Hà Nội",
                        LocalDate.of(1990, 1, 1));
        Customer savedCustomer = customerRepository.save(customer);

        Account account =
                new Account(
                        "100999999999",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        Account savedAccount = accountRepository.save(account);

        int threadCount = 10;
        BigDecimal depositAmount = new BigDecimal("100000.00");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(
                    () -> {
                        DepositRequest request =
                                new DepositRequest(
                                        savedAccount.getId(),
                                        depositAmount,
                                        "Hà Nội",
                                        "Nạp tiền đồng thời");
                        transactionService.deposit(request);
                        return null;
                    });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        Account updatedAccount = accountRepository.findById(savedAccount.getId()).orElseThrow();
        BigDecimal expectedBalance = depositAmount.multiply(BigDecimal.valueOf(threadCount));
        assertThat(updatedAccount.getBalance()).isEqualByComparingTo(expectedBalance);
    }
}
