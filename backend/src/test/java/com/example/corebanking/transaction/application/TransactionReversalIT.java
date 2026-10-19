package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransactionReversalIT extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @DisplayName(
            "Transfer reversal restores sender balance and fee, debits receiver, marks original"
                    + " REVERSED")
    void transferReversal_shouldRestoreBalancesAndMarkOriginalReversed() {
        Customer customer =
                new Customer(
                        "CUS9990004",
                        "Phan Văn Đảo",
                        "000999888444",
                        "phanvandao@example.com",
                        "0900000004",
                        "Hà Nội",
                        "Hà Nội",
                        LocalDate.of(1992, 5, 10));
        Customer savedCustomer = customerRepository.save(customer);

        Account accountA =
                new Account(
                        "100999999003",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountA.credit(Money.of("10000000.00")); // 10,000,000
        Account savedA = accountRepository.save(accountA);

        Account accountB =
                new Account(
                        "100999999004",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountB.credit(Money.of("5000000.00")); // 5,000,000
        Account savedB = accountRepository.save(accountB);

        // A transfers 2,000,000 to B. Fee for 2M is 2,000. Total deducted from A: 2,002,000.
        TransferRequest transferRequest =
                new TransferRequest(
                        savedA.getId(),
                        savedB.getId(),
                        new BigDecimal("2000000.00"),
                        "Hà Nội",
                        "Chuyển tiền mua hàng");

        TransactionResponse transferResponse = transactionService.transfer(transferRequest);
        Long originalTxnId = transferResponse.id();

        Account afterTransferA = accountRepository.findById(savedA.getId()).orElseThrow();
        Account afterTransferB = accountRepository.findById(savedB.getId()).orElseThrow();
        assertThat(afterTransferA.getBalance()).isEqualByComparingTo("7998000.00"); // 10M - 2.002M
        assertThat(afterTransferB.getBalance()).isEqualByComparingTo("7000000.00"); // 5M + 2M

        // Execute Reversal
        TransactionResponse reversalResponse = transactionService.reverseTransaction(originalTxnId);

        assertThat(reversalResponse.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversalResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reversalResponse.reversalOfId()).isEqualTo(originalTxnId);
        assertThat(reversalResponse.amount()).isEqualByComparingTo("2000000.00");
        assertThat(reversalResponse.fee()).isEqualByComparingTo("2000.00");
        assertThat(reversalResponse.systemBalanceDelta())
                .isEqualByComparingTo("2000.00"); // bank fee refunded

        // Check account balances
        Account afterReversalA = accountRepository.findById(savedA.getId()).orElseThrow();
        Account afterReversalB = accountRepository.findById(savedB.getId()).orElseThrow();
        assertThat(afterReversalA.getBalance()).isEqualByComparingTo("10000000.00"); // restored
        assertThat(afterReversalB.getBalance()).isEqualByComparingTo("5000000.00"); // deducted back

        // Check original transaction is REVERSED
        Transaction originalTxn = transactionRepository.findById(originalTxnId).orElseThrow();
        assertThat(originalTxn.getStatus()).isEqualTo(TransactionStatus.REVERSED);

        // Attempting to reverse again throws ALREADY_REVERSED
        assertThatThrownBy(() -> transactionService.reverseTransaction(originalTxnId))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ALREADY_REVERSED));
    }

    @Test
    @DisplayName(
            "Concurrent reversal attempts on the same transaction allow exactly one success and"
                    + " reject the second with ALREADY_REVERSED under pessimistic lock")
    void concurrentReversals_shouldAllowExactlyOneSuccess() throws Exception {
        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9990044",
                                "Phan Đồng Thời",
                                "000999888445",
                                "phandongthoi@example.com",
                                "0900000045",
                                "Hà Nội",
                                "Hà Nội",
                                LocalDate.of(1992, 5, 10)));

        Account account =
                new Account(
                        "100999999044",
                        customer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        account.credit(Money.of("10000000.00"));
        Account savedAccount = accountRepository.save(account);

        TransactionResponse deposit =
                transactionService.deposit(
                        new com.example.corebanking.transaction.api.dto.DepositRequest(
                                savedAccount.getId(),
                                new BigDecimal("2000000.00"),
                                "Hà Nội",
                                "Nạp thử đảo đồng thời"));

        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger successes =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger alreadyReversedErrors =
                new java.util.concurrent.atomic.AtomicInteger(0);

        Runnable task =
                () -> {
                    try {
                        startLatch.await();
                        transactionService.reverseTransaction(deposit.id());
                        successes.incrementAndGet();
                    } catch (BusinessRuleException ex) {
                        if (ex.getErrorCode() == ErrorCode.ALREADY_REVERSED) {
                            alreadyReversedErrors.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                };

        var f1 = executor.submit(task);
        var f2 = executor.submit(task);
        startLatch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(alreadyReversedErrors.get()).isEqualTo(1);
        Account reloaded = accountRepository.findById(savedAccount.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("10000000.00");
    }
}
