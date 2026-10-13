package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.common.idempotency.IdempotencyKeyId;
import com.example.corebanking.common.idempotency.IdempotencyRepository;
import com.example.corebanking.common.idempotency.IdempotencyResult;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;

    @Test
    @DisplayName("Exact retry of transfer replays cached response and does not double-spend")
    void replayTransfer_shouldNotDeductMoneyTwice() {
        Customer customer =
                new Customer(
                        "CUS9990005",
                        "Đỗ Trùng Lặp",
                        "000999888555",
                        "dotrunglap@example.com",
                        "0900000005",
                        "Hà Nội",
                        "Hà Nội",
                        LocalDate.of(1990, 1, 1));
        Customer savedCustomer = customerRepository.save(customer);

        Account accountA =
                new Account(
                        "100999999005",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountA.credit(Money.of("10000000.00")); // 10,000,000
        Account savedA = accountRepository.save(accountA);

        Account accountB =
                new Account(
                        "100999999006",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountB.credit(Money.of("5000000.00")); // 5,000,000
        Account savedB = accountRepository.save(accountB);

        String idemKey = "test-transfer-replay-001";
        TransferRequest request =
                new TransferRequest(
                        savedA.getId(),
                        savedB.getId(),
                        new BigDecimal("2000000.00"),
                        "Hà Nội",
                        "Chuyển tiền chống trùng");

        long initialTxnCount = transactionRepository.count();

        // 1st Execution
        IdempotencyResult<TransactionResponse> result1 =
                transactionService.transfer(idemKey, request);
        assertThat(result1.replayed()).isFalse();
        assertThat(result1.data().id()).isNotNull();

        Account afterFirstA = accountRepository.findById(savedA.getId()).orElseThrow();
        Account afterFirstB = accountRepository.findById(savedB.getId()).orElseThrow();
        // 10M - (2M + fee 2000) = 7,998,000
        assertThat(afterFirstA.getBalance()).isEqualByComparingTo("7998000.00");
        // 5M + 2M = 7,000,000
        assertThat(afterFirstB.getBalance()).isEqualByComparingTo("7000000.00");
        assertThat(transactionRepository.count()).isEqualTo(initialTxnCount + 1);

        // 2nd Execution (Replay with identical key and payload)
        IdempotencyResult<TransactionResponse> result2 =
                transactionService.transfer(idemKey, request);
        assertThat(result2.replayed()).isTrue();
        assertThat(result2.data().id()).isEqualTo(result1.data().id());
        assertThat(result2.data().referenceNo()).isEqualTo(result1.data().referenceNo());

        // Balance and transaction records must remain unchanged (no double debit)
        Account afterSecondA = accountRepository.findById(savedA.getId()).orElseThrow();
        Account afterSecondB = accountRepository.findById(savedB.getId()).orElseThrow();
        assertThat(afterSecondA.getBalance()).isEqualByComparingTo("7998000.00");
        assertThat(afterSecondB.getBalance()).isEqualByComparingTo("7000000.00");
        assertThat(transactionRepository.count()).isEqualTo(initialTxnCount + 1);

        // Verify idempotency record in DB
        IdempotencyKeyId keyId = new IdempotencyKeyId(idemKey, "DEFAULT");
        assertThat(idempotencyRepository.findById(keyId)).isPresent();
    }

    @Test
    @DisplayName(
            "Business failure rolls back idempotency record, allowing successful retry with same"
                    + " key")
    void failedTransferThenRetryWithSameKey_shouldSucceed() {
        Customer customer =
                new Customer(
                        "CUS9990006",
                        "Võ Thử Lại",
                        "000999888666",
                        "vothulai@example.com",
                        "0900000006",
                        "Đà Nẵng",
                        "Đà Nẵng",
                        LocalDate.of(1995, 3, 15));
        Customer savedCustomer = customerRepository.save(customer);

        Account accountA =
                new Account(
                        "100999999007",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountA.credit(Money.of("500000.00")); // 500,000
        Account savedA = accountRepository.save(accountA);

        Account accountB =
                new Account(
                        "100999999008",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        Account savedB = accountRepository.save(accountB);

        String idemKey = "test-fail-retry-002";
        TransferRequest request =
                new TransferRequest(
                        savedA.getId(),
                        savedB.getId(),
                        new BigDecimal("2000000.00"), // Exceeds balance
                        "Đà Nẵng",
                        "Chuyển vượt số dư");

        // 1st Execution fails due to INSUFFICIENT_BALANCE
        assertThatThrownBy(() -> transactionService.transfer(idemKey, request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        // Key must NOT be persisted because transaction rolled back
        IdempotencyKeyId keyId = new IdempotencyKeyId(idemKey, "DEFAULT");
        assertThat(idempotencyRepository.findById(keyId)).isEmpty();

        // Customer deposits money into Account A
        transactionService.deposit(
                new DepositRequest(
                        savedA.getId(), new BigDecimal("5000000.00"), "Đà Nẵng", "Nạp bù số dư"));

        // 2nd Execution with same key now succeeds as a fresh transaction
        IdempotencyResult<TransactionResponse> result =
                transactionService.transfer(idemKey, request);
        assertThat(result.replayed()).isFalse();
        assertThat(result.data().id()).isNotNull();

        // Key is now recorded
        assertThat(idempotencyRepository.findById(keyId)).isPresent();
    }

    @Test
    @DisplayName("Reusing key with different payload throws IDEMPOTENCY_KEY_REUSED")
    void transferWithSameKeyDifferentPayload_shouldReturn422() {
        Customer customer =
                new Customer(
                        "CUS9990007",
                        "Lê Tái Dùng",
                        "000999888777",
                        "letaidung@example.com",
                        "0900000007",
                        "TP.HCM",
                        "TP.HCM",
                        LocalDate.of(1988, 8, 8));
        Customer savedCustomer = customerRepository.save(customer);

        Account accountA =
                new Account(
                        "100999999009",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        accountA.credit(Money.of("10000000.00")); // 10,000,000
        Account savedA = accountRepository.save(accountA);

        Account accountB =
                new Account(
                        "100999999010",
                        savedCustomer.getId(),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.now());
        Account savedB = accountRepository.save(accountB);

        String idemKey = "test-reused-payload-003";
        TransferRequest req1 =
                new TransferRequest(
                        savedA.getId(),
                        savedB.getId(),
                        new BigDecimal("1000000.00"),
                        "TP.HCM",
                        "Lần 1");
        TransferRequest req2 =
                new TransferRequest(
                        savedA.getId(),
                        savedB.getId(),
                        new BigDecimal("2000000.00"), // Different amount
                        "TP.HCM",
                        "Lần 2");

        IdempotencyResult<TransactionResponse> res1 = transactionService.transfer(idemKey, req1);
        assertThat(res1.replayed()).isFalse();

        // 2nd call with same key but different amount
        assertThatThrownBy(() -> transactionService.transfer(idemKey, req2))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
