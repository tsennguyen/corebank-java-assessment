package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.PaymentRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.api.dto.UpdateTransactionRequest;
import com.example.corebanking.transaction.api.dto.WithdrawalRequest;
import com.example.corebanking.transaction.domain.FeePolicy;
import com.example.corebanking.transaction.domain.LimitPolicy;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionReferenceGenerator referenceGenerator;
    @Mock private FeePolicy feePolicy;
    @Mock private LimitPolicy limitPolicy;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService =
                new TransactionService(
                        accountRepository,
                        transactionRepository,
                        referenceGenerator,
                        feePolicy,
                        limitPolicy,
                        clock,
                        eventPublisher);
    }

    private Account createAccount(
            Long id, BigDecimal initialBalance, BigDecimal txnLimit, BigDecimal dailyLimit) {
        Account account =
                new Account(
                        "1000000000" + String.format("%02d", id),
                        1L,
                        txnLimit,
                        dailyLimit,
                        LocalDate.of(2026, 10, 9));
        try {
            java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(account, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            account.credit(Money.of(initialBalance));
        }
        return account;
    }

    private Account createActiveAccount() {
        return createAccount(
                1L, BigDecimal.ZERO, new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));
    }

    // ==========================================
    // DEPOSIT TESTS
    // ==========================================
    @Nested
    @DisplayName("deposit() Tests")
    class DepositTests {

        @Test
        @DisplayName("deposit() credits account and persists completed transaction record")
        void deposit_whenAccountActive_shouldIncreaseBalanceAndSaveTransaction() {
            Account account = createActiveAccount();
            DepositRequest request =
                    new DepositRequest(
                            1L, new BigDecimal("1500000.00"), "Hà Nội", "Nạp tiền ban đầu");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-ABC123DEF456");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TransactionResponse response = transactionService.deposit(request);

            assertThat(account.getBalance()).isEqualByComparingTo("1500000.00");
            assertThat(response.referenceNo()).isEqualTo("TXN-20261012-ABC123DEF456");
            assertThat(response.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.amount()).isEqualByComparingTo("1500000.00");
            assertThat(response.fee()).isEqualByComparingTo("0.00");
            assertThat(response.targetAccountId()).isEqualTo(1L);
            assertThat(response.targetBalanceAfter()).isEqualByComparingTo("1500000.00");
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("1500000.00");
            assertThat(response.location()).isEqualTo("Hà Nội");
            assertThat(response.description()).isEqualTo("Nạp tiền ban đầu");

            ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            Transaction saved = txCaptor.getValue();
            assertThat(saved.getTargetAccountId()).isEqualTo(1L);
            assertThat(saved.getAmount()).isEqualByComparingTo("1500000.00");

            verify(eventPublisher).publishEvent(any(TransactionCompletedEvent.class));
        }

        @Test
        @DisplayName("deposit() throws RESOURCE_NOT_FOUND when account does not exist")
        void deposit_whenAccountNotFound_shouldThrowResourceNotFound() {
            DepositRequest request =
                    new DepositRequest(999L, new BigDecimal("100000.00"), "HN", "Nạp");
            when(accountRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.deposit(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Account not found with identifier: 999");
        }

        @Test
        @DisplayName("deposit() throws ACCOUNT_NOT_ACTIVE when account is closed")
        void deposit_whenAccountClosed_shouldThrowAccountNotActive() {
            Account account = createActiveAccount();
            account.close(Instant.parse("2026-10-09T08:00:00Z"));

            DepositRequest request =
                    new DepositRequest(1L, new BigDecimal("100000.00"), "HN", "Nạp");
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> transactionService.deposit(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
        }
    }

    // ==========================================
    // WITHDRAW TESTS
    // ==========================================
    @Nested
    @DisplayName("withdraw() Tests")
    class WithdrawTests {

        @Test
        @DisplayName("withdraw() debits principal + fee and persists withdrawal transaction")
        void withdraw_whenValid_shouldDebitBalanceWithFeeAndSaveTransaction() {
            Account account =
                    createAccount(
                            1L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            WithdrawalRequest request =
                    new WithdrawalRequest(
                            1L, new BigDecimal("1000000.00"), "ATM Q1", "Rút tiền mặt");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.WITHDRAWAL, Money.of("1000000.00")))
                    .thenReturn(Money.of("2000.00"));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-WTH001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TransactionResponse response = transactionService.withdraw(request);

            // balance was 5,000,000 - (1,000,000 + 2,000) = 3,998,000.00
            assertThat(account.getBalance()).isEqualByComparingTo("3998000.00");
            assertThat(response.referenceNo()).isEqualTo("TXN-20261012-WTH001");
            assertThat(response.type()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.amount()).isEqualByComparingTo("1000000.00");
            assertThat(response.fee()).isEqualByComparingTo("2000.00");
            assertThat(response.sourceAccountId()).isEqualTo(1L);
            assertThat(response.sourceBalanceAfter()).isEqualByComparingTo("3998000.00");
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("-1002000.00");

            verify(limitPolicy).validateLimits(account, Money.of("1000000.00"), Money.of("0.00"));
            verify(eventPublisher).publishEvent(any(TransactionCompletedEvent.class));
        }

        @Test
        @DisplayName("withdraw() throws RESOURCE_NOT_FOUND when account does not exist")
        void withdraw_whenAccountNotFound_shouldThrowResourceNotFound() {
            WithdrawalRequest request =
                    new WithdrawalRequest(999L, new BigDecimal("1000000.00"), "ATM", "Rút");
            when(accountRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.withdraw(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Account not found with identifier: 999");
        }

        @Test
        @DisplayName(
                "withdraw() throws INSUFFICIENT_BALANCE when balance is less than amount + fee")
        void withdraw_whenInsufficientBalance_shouldThrowInsufficientBalance() {
            Account account =
                    createAccount(
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            WithdrawalRequest request =
                    new WithdrawalRequest(1L, new BigDecimal("1000000.00"), "ATM", "Rút hết");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.WITHDRAWAL, Money.of("1000000.00")))
                    .thenReturn(Money.of("2000.00"));

            assertThatThrownBy(() -> transactionService.withdraw(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        }

        @Test
        @DisplayName("withdraw() throws LIMIT_EXCEEDED when limitPolicy rejects transaction")
        void withdraw_whenLimitExceeded_shouldThrowLimitExceeded() {
            Account account =
                    createAccount(
                            1L,
                            new BigDecimal("100000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            WithdrawalRequest request =
                    new WithdrawalRequest(1L, new BigDecimal("60000000.00"), "ATM", "Vượt hạn mức");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            doThrow(new BusinessRuleException(ErrorCode.LIMIT_EXCEEDED, "Exceeded"))
                    .when(limitPolicy)
                    .validateLimits(any(), any(), any());

            assertThatThrownBy(() -> transactionService.withdraw(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        }
    }

    // ==========================================
    // TRANSFER TESTS
    // ==========================================
    @Nested
    @DisplayName("transfer() Tests")
    class TransferTests {

        @Test
        @DisplayName("transfer() acquires pessimistic locks in ascending ID order (fromId < toId)")
        void transfer_whenFromIdLessThanToId_shouldLockInAscendingOrder() {
            Account source =
                    createAccount(
                            1L,
                            new BigDecimal("10000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            Account target =
                    createAccount(
                            2L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            TransferRequest request =
                    new TransferRequest(
                            1L, 2L, new BigDecimal("3000000.00"), "Mobile", "Chuyển tiền");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(source));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.TRANSFER, Money.of("3000000.00")))
                    .thenReturn(Money.of("2000.00"));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-TRF001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TransactionResponse response = transactionService.transfer(request);

            // Source debited: 10,000,000 - 3,002,000 = 6,998,000.00
            assertThat(source.getBalance()).isEqualByComparingTo("6998000.00");
            // Target credited: 5,000,000 + 3,000,000 = 8,000,000.00
            assertThat(target.getBalance()).isEqualByComparingTo("8000000.00");
            assertThat(response.sourceBalanceAfter()).isEqualByComparingTo("6998000.00");
            assertThat(response.targetBalanceAfter()).isEqualByComparingTo("8000000.00");
            assertThat(response.fee()).isEqualByComparingTo("2000.00");
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("-2000.00");

            // Verify strict lock order: 1L then 2L
            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdForUpdate(1L);
            inOrder.verify(accountRepository).findByIdForUpdate(2L);

            verify(eventPublisher).publishEvent(any(TransactionCompletedEvent.class));
        }

        @Test
        @DisplayName(
                "transfer() acquires pessimistic locks in ascending ID order (fromId > toId,"
                        + " NC-01)")
        void transfer_whenFromIdGreaterThanToId_shouldLockInAscendingOrder() {
            Account source =
                    createAccount(
                            2L,
                            new BigDecimal("10000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            Account target =
                    createAccount(
                            1L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            TransferRequest request =
                    new TransferRequest(
                            2L, 1L, new BigDecimal("3000000.00"), "Mobile", "Chuyển tiền");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(source));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(2L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.TRANSFER, Money.of("3000000.00")))
                    .thenReturn(Money.of("2000.00"));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-TRF002");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TransactionResponse response = transactionService.transfer(request);

            assertThat(source.getBalance()).isEqualByComparingTo("6998000.00");
            assertThat(target.getBalance()).isEqualByComparingTo("8000000.00");
            assertThat(response.sourceAccountId()).isEqualTo(2L);
            assertThat(response.targetAccountId()).isEqualTo(1L);

            // Crucial deadlock prevention verification: must lock min(1, 2) first, then max(1, 2)
            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdForUpdate(1L);
            inOrder.verify(accountRepository).findByIdForUpdate(2L);
        }

        @Test
        @DisplayName("transfer() throws SAME_ACCOUNT_TRANSFER when source and target IDs match")
        void transfer_whenSameSourceAndTarget_shouldThrowSameAccountTransfer() {
            TransferRequest request =
                    new TransferRequest(
                            1L, 1L, new BigDecimal("100000.00"), "Mobile", "Chuyển chính mình");

            assertThatThrownBy(() -> transactionService.transfer(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER));
        }

        @Test
        @DisplayName("transfer() throws RESOURCE_NOT_FOUND when source account does not exist")
        void transfer_whenSourceNotFound_shouldThrowResourceNotFound() {
            TransferRequest request =
                    new TransferRequest(999L, 1L, new BigDecimal("100000.00"), "Mobile", "Chuyển");
            when(accountRepository.findByIdForUpdate(1L))
                    .thenReturn(Optional.of(createActiveAccount()));
            when(accountRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.transfer(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Account not found with identifier: 999");
        }

        @Test
        @DisplayName(
                "transfer() throws INSUFFICIENT_BALANCE when source balance cannot cover amount +"
                        + " fee")
        void transfer_whenInsufficientBalance_shouldThrowInsufficientBalance() {
            Account source =
                    createAccount(
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            Account target =
                    createAccount(
                            2L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            TransferRequest request =
                    new TransferRequest(
                            1L, 2L, new BigDecimal("1000000.00"), "Mobile", "Chuyển hết");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(source));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.TRANSFER, Money.of("1000000.00")))
                    .thenReturn(Money.of("2000.00"));

            assertThatThrownBy(() -> transactionService.transfer(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        }
    }

    // ==========================================
    // PAYMENT TESTS
    // ==========================================
    @Nested
    @DisplayName("payment() Tests")
    class PaymentTests {

        @Test
        @DisplayName(
                "payment() debits account, appends recipientInfo to description, and saves"
                        + " transaction")
        void payment_whenValid_shouldDebitBalanceAndAppendRecipientInfo() {
            Account account =
                    createAccount(
                            1L,
                            new BigDecimal("2000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            PaymentRequest request =
                    new PaymentRequest(
                            1L,
                            new BigDecimal("500000.00"),
                            "Web Portal",
                            "Tiền điện tháng 10",
                            "EVN HCMC");

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.PAYMENT, Money.of("500000.00")))
                    .thenReturn(Money.of("0.00"));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-PAY001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TransactionResponse response = transactionService.payment(request);

            assertThat(account.getBalance()).isEqualByComparingTo("1500000.00");
            assertThat(response.referenceNo()).isEqualTo("TXN-20261012-PAY001");
            assertThat(response.type()).isEqualTo(TransactionType.PAYMENT);
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.amount()).isEqualByComparingTo("500000.00");
            assertThat(response.fee()).isEqualByComparingTo("0.00");
            assertThat(response.description()).isEqualTo("Tiền điện tháng 10 | EVN HCMC");
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("-500000.00");

            verify(limitPolicy).validateLimits(account, Money.of("500000.00"), Money.of("0.00"));
            verify(eventPublisher).publishEvent(any(TransactionCompletedEvent.class));
        }

        @Test
        @DisplayName("payment() throws RESOURCE_NOT_FOUND when source account does not exist")
        void payment_whenAccountNotFound_shouldThrowResourceNotFound() {
            PaymentRequest request =
                    new PaymentRequest(
                            999L, new BigDecimal("500000.00"), "Web", "Tiền điện", "EVN");
            when(accountRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.payment(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Account not found with identifier: 999");
        }

        @Test
        @DisplayName(
                "payment() throws VALIDATION_FAILED when combined description and recipientInfo"
                        + " exceed 500 characters")
        void payment_whenCombinedDescriptionExceeds500Chars_shouldThrowValidationFailed() {
            Account account =
                    createAccount(
                            1L,
                            new BigDecimal("2000000.00"),
                            new BigDecimal("50000000.00"),
                            new BigDecimal("100000000.00"));
            PaymentRequest request =
                    new PaymentRequest(
                            1L,
                            new BigDecimal("500000.00"),
                            "Web",
                            "A".repeat(450),
                            "B".repeat(100));

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
            when(transactionRepository.sumOutgoingAmountSince(
                            eq(1L), eq(TransactionStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(feePolicy.calculateFee(TransactionType.PAYMENT, Money.of("500000.00")))
                    .thenReturn(Money.of("0.00"));
            when(referenceGenerator.generate()).thenReturn("TXN-20261012-PAY002");

            assertThatThrownBy(() -> transactionService.payment(request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    // ==========================================
    // QUERY & SEARCH TESTS
    // ==========================================
    @Nested
    @DisplayName("Query and Search Tests")
    class QueryAndSearchTests {

        @Test
        @DisplayName("getTransactionById() returns transaction details when found")
        void getTransactionById_whenExists_shouldReturnResponse() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-123",
                            1L,
                            new BigDecimal("500000.00"),
                            new BigDecimal("500000.00"),
                            "HN",
                            "Nạp");
            when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));

            TransactionResponse response = transactionService.getTransactionById(1L);
            assertThat(response.referenceNo()).isEqualTo("TXN-123");
            assertThat(response.amount()).isEqualByComparingTo("500000.00");
        }

        @Test
        @DisplayName(
                "getTransactionById() throws RESOURCE_NOT_FOUND when transaction does not exist")
        void getTransactionById_whenNotFound_shouldThrowResourceNotFound() {
            when(transactionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.getTransactionById(999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Transaction not found with identifier: 999");
        }

        @Test
        @DisplayName(
                "getAccountTransactions() throws RESOURCE_NOT_FOUND when account does not exist")
        void getAccountTransactions_whenAccountNotFound_shouldThrowResourceNotFound() {
            when(accountRepository.existsById(999L)).thenReturn(false);

            Pageable pageable = PageRequest.of(0, 20);
            assertThatThrownBy(() -> transactionService.getAccountTransactions(999L, pageable))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Account not found with identifier: 999");
        }

        @Test
        @DisplayName("getAccountTransactions() returns paginated list when account exists")
        void getAccountTransactions_whenValid_shouldReturnPaginatedResponse() {
            when(accountRepository.existsById(1L)).thenReturn(true);
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-123",
                            1L,
                            new BigDecimal("500000.00"),
                            new BigDecimal("500000.00"),
                            "HN",
                            "Nạp");
            when(transactionRepository.findByAccountId(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(tx), pageable, 1));

            PageResponse<TransactionResponse> response =
                    transactionService.getAccountTransactions(1L, pageable);

            assertThat(response.content()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(1);
            assertThat(response.content().getFirst().referenceNo()).isEqualTo("TXN-123");
        }

        @Test
        @DisplayName("getAccountTransactions() without sort defaults to createdAt DESC, id DESC")
        void getAccountTransactions_withoutSort_shouldDefaultToCreatedAtDescAndIdDesc() {
            when(accountRepository.existsById(1L)).thenReturn(true);
            Pageable unsorted = PageRequest.of(0, 20);
            when(transactionRepository.findByAccountId(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            transactionService.getAccountTransactions(1L, unsorted);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findByAccountId(eq(1L), captor.capture());
            Sort sort = captor.getValue().getSort();
            assertThat(sort.getOrderFor("createdAt")).isNotNull();
            assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id")).isNotNull();
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("getAccountTransactions() with amount sort appends id DESC tie-breaker")
        void getAccountTransactions_withAmountSort_shouldAppendIdDesc() {
            when(accountRepository.existsById(1L)).thenReturn(true);
            Pageable amountSorted = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "amount"));
            when(transactionRepository.findByAccountId(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            transactionService.getAccountTransactions(1L, amountSorted);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findByAccountId(eq(1L), captor.capture());
            Sort sort = captor.getValue().getSort();
            assertThat(sort.getOrderFor("amount")).isNotNull();
            assertThat(sort.getOrderFor("amount").getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(sort.getOrderFor("id")).isNotNull();
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("searchTransactions() rejects invalid sort fields")
        void searchTransactions_withInvalidSortField_shouldThrowInvalidSortField() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "invalidField"));
            TransactionSearchCriteria criteria = new TransactionSearchCriteria(null, null);

            assertThatThrownBy(() -> transactionService.searchTransactions(criteria, pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_SORT_FIELD));
        }

        @Test
        @DisplayName("searchTransactions() rejects page size exceeding 100")
        void searchTransactions_withPageSizeExceeding100_shouldThrowValidationFailed() {
            Pageable pageable = PageRequest.of(0, 101);
            TransactionSearchCriteria criteria = new TransactionSearchCriteria(null, null);

            assertThatThrownBy(() -> transactionService.searchTransactions(criteria, pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("searchTransactions() returns matching transactions")
        void searchTransactions_whenValid_shouldReturnResults() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            TransactionSearchCriteria criteria =
                    new TransactionSearchCriteria(1L, TransactionType.DEPOSIT);
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-123",
                            1L,
                            new BigDecimal("500000.00"),
                            new BigDecimal("500000.00"),
                            "HN",
                            "Nạp");

            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(tx), pageable, 1));

            PageResponse<TransactionResponse> response =
                    transactionService.searchTransactions(criteria, pageable);

            assertThat(response.content()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("updateTransactionMetadata Tests")
    class UpdateTransactionMetadataTests {

        @Test
        @DisplayName("updateTransactionMetadata updates description and location successfully")
        void updateTransactionMetadata_whenValid_shouldUpdateAndReturn() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-UPD001",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Old Loc",
                            "Old Desc");
            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(tx));
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UpdateTransactionRequest request = new UpdateTransactionRequest("New Desc", "New Loc");
            TransactionResponse response =
                    transactionService.updateTransactionMetadata(1L, request);

            assertThat(response.description()).isEqualTo("New Desc");
            assertThat(response.location()).isEqualTo("New Loc");
            verify(transactionRepository).saveAndFlush(tx);
        }

        @Test
        @DisplayName("updateTransactionMetadata rejects requests with immutable fields")
        void updateTransactionMetadata_withImmutableField_shouldThrowImmutableField() {
            UpdateTransactionRequest request =
                    new UpdateTransactionRequest(
                            "Desc",
                            "Loc",
                            new BigDecimal("5000.00"),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);

            assertThatThrownBy(() -> transactionService.updateTransactionMetadata(1L, request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.IMMUTABLE_FIELD));
        }

        @Test
        @DisplayName(
                "updateTransactionMetadata throws ResourceNotFoundException when transaction not"
                        + " found")
        void updateTransactionMetadata_whenNotFound_shouldThrowNotFound() {
            when(transactionRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());
            UpdateTransactionRequest request = new UpdateTransactionRequest("Desc", "Loc");

            assertThatThrownBy(() -> transactionService.updateTransactionMetadata(999L, request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName(
                "updateTransactionMetadata throws INVALID_STATUS_TRANSITION when transaction is"
                        + " already REVERSED")
        void updateTransactionMetadata_whenReversed_shouldThrowInvalidStatusTransition() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-UPD002",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            tx.markReversed();
            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(tx));
            UpdateTransactionRequest request = new UpdateTransactionRequest("New Desc", "New Loc");

            assertThatThrownBy(() -> transactionService.updateTransactionMetadata(1L, request))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("reverseTransaction Tests")
    class ReverseTransactionTests {

        @Test
        @DisplayName(
                "reverseTransaction throws ResourceNotFoundException when transaction does not"
                        + " exist")
        void reverseTransaction_whenNotFound_shouldThrowNotFound() {
            when(transactionRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.reverseTransaction(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("reverseTransaction throws ALREADY_REVERSED when status is REVERSED")
        void reverseTransaction_whenAlreadyReversed_shouldThrowAlreadyReversed() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-REV001",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            tx.markReversed();
            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(tx));

            assertThatThrownBy(() -> transactionService.reverseTransaction(1L))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.ALREADY_REVERSED));
        }

        @Test
        @DisplayName(
                "reverseTransaction throws ALREADY_REVERSED when reversalOfId already exists in DB")
        void reverseTransaction_whenReversalExistsInRepo_shouldThrowAlreadyReversed() {
            Transaction tx =
                    Transaction.createDeposit(
                            "TXN-REV002",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(tx));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(true);

            assertThatThrownBy(() -> transactionService.reverseTransaction(1L))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.ALREADY_REVERSED));
        }

        @Test
        @DisplayName(
                "reverseTransaction throws INVALID_STATUS_TRANSITION when transaction is a"
                        + " REVERSAL")
        void reverseTransaction_whenReversalType_shouldThrowInvalidStatusTransition() {
            Transaction orig =
                    Transaction.createDeposit(
                            "TXN-REV003",
                            1L,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "Loc",
                            "Desc");
            Transaction reversalTx =
                    Transaction.createDepositReversal(
                            "TXN-REV003-R", orig, BigDecimal.ZERO, null, null);
            when(transactionRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(reversalTx));

            assertThatThrownBy(() -> transactionService.reverseTransaction(2L))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }

        @Test
        @DisplayName(
                "reverseTransaction for DEPOSIT debits target account and marks original REVERSED")
        void reverseTransaction_deposit_shouldDebitTargetAndMarkReversed() {
            Account targetAccount =
                    new Account(
                            "100000000001",
                            10L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            targetAccount.credit(Money.of(new BigDecimal("1500000.00"))); // balance 1,500,000

            Transaction orig =
                    Transaction.createDeposit(
                            "TXN-DEP001",
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("1000000.00"),
                            "HN",
                            "Deposit");

            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(orig));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(false);
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(targetAccount));
            when(referenceGenerator.generate()).thenReturn("TXN-REV-DEP-001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionResponse response = transactionService.reverseTransaction(1L);

            assertThat(orig.getStatus()).isEqualTo(TransactionStatus.REVERSED);
            assertThat(targetAccount.getBalance()).isEqualByComparingTo("500000.00");
            assertThat(response.type()).isEqualTo(TransactionType.REVERSAL);
            assertThat(response.amount()).isEqualByComparingTo("1000000.00");
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("-1000000.00");
            verify(eventPublisher).publishEvent(any(TransactionCompletedEvent.class));
        }

        @Test
        @DisplayName(
                "reverseTransaction for WITHDRAWAL credits source account with amount plus fee")
        void reverseTransaction_withdrawal_shouldCreditSourceWithAmountAndFee() {
            Account sourceAccount =
                    new Account(
                            "100000000001",
                            10L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            sourceAccount.credit(Money.of(new BigDecimal("5000000.00")));
            sourceAccount.debit(Money.of(new BigDecimal("1002000.00"))); // balance 3,998,000

            Transaction orig =
                    Transaction.createWithdrawal(
                            "TXN-WTH001",
                            1L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("3998000.00"),
                            "ATM",
                            "Withdrawal");

            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(orig));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(false);
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sourceAccount));
            when(referenceGenerator.generate()).thenReturn("TXN-REV-WTH-001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionResponse response = transactionService.reverseTransaction(1L);

            assertThat(orig.getStatus()).isEqualTo(TransactionStatus.REVERSED);
            assertThat(sourceAccount.getBalance()).isEqualByComparingTo("5000000.00"); // restored
            assertThat(response.type()).isEqualTo(TransactionType.REVERSAL);
            assertThat(response.systemBalanceDelta()).isEqualByComparingTo("1002000.00");
        }

        @Test
        @DisplayName("reverseTransaction for PAYMENT credits source account with amount plus fee")
        void reverseTransaction_payment_shouldCreditSourceWithAmountAndFee() {
            Account sourceAccount =
                    new Account(
                            "100000000001",
                            10L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            sourceAccount.credit(Money.of(new BigDecimal("500000.00"))); // balance 500,000

            Transaction orig =
                    Transaction.createPayment(
                            "TXN-PAY001",
                            1L,
                            new BigDecimal("500000.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            "Online",
                            "Bill payment");

            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(orig));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(false);
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sourceAccount));
            when(referenceGenerator.generate()).thenReturn("TXN-REV-PAY-001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionResponse response = transactionService.reverseTransaction(1L);

            assertThat(orig.getStatus()).isEqualTo(TransactionStatus.REVERSED);
            assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000000.00");
            assertThat(response.type()).isEqualTo(TransactionType.REVERSAL);
        }

        @Test
        @DisplayName(
                "reverseTransaction for TRANSFER debits target and credits source under ascending"
                        + " lock order")
        void reverseTransaction_transfer_shouldDebitTargetCreditSourceWithAscendingLock() {
            Account account1 =
                    new Account(
                            "100000000001",
                            10L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            account1.credit(Money.of(new BigDecimal("1000000.00"))); // A has 1,000,000

            Account account2 =
                    new Account(
                            "100000000002",
                            20L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            account2.credit(Money.of(new BigDecimal("5000000.00"))); // B has 5,000,000

            // Transfer was A (1L) -> B (2L), amount 3,000,000, fee 2,000
            Transaction orig =
                    Transaction.createTransfer(
                            "TXN-TRF001",
                            1L,
                            2L,
                            new BigDecimal("3000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("1000000.00"),
                            new BigDecimal("5000000.00"),
                            "Online",
                            "Transfer");

            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(orig));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(false);
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account1));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(account2));
            when(referenceGenerator.generate()).thenReturn("TXN-REV-TRF-001");
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionResponse response = transactionService.reverseTransaction(1L);

            // Verify ascending lock order (1L then 2L)
            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdForUpdate(1L);
            inOrder.verify(accountRepository).findByIdForUpdate(2L);

            assertThat(orig.getStatus()).isEqualTo(TransactionStatus.REVERSED);
            assertThat(account2.getBalance())
                    .isEqualByComparingTo("2000000.00"); // B debited 3,000,000
            assertThat(account1.getBalance())
                    .isEqualByComparingTo("4002000.00"); // A credited 3,002,000 (amount + fee)
            assertThat(response.type()).isEqualTo(TransactionType.REVERSAL);
            assertThat(response.systemBalanceDelta())
                    .isEqualByComparingTo("2000.00"); // fee refunded
        }

        @Test
        @DisplayName(
                "reverseTransaction throws INSUFFICIENT_BALANCE when target account has spent the"
                        + " money")
        void reverseTransaction_whenTargetHasInsufficientBalance_shouldThrowInsufficientBalance() {
            Account account1 =
                    new Account(
                            "100000000001",
                            10L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());

            Account account2 =
                    new Account(
                            "100000000002",
                            20L,
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"),
                            LocalDate.now());
            // B has 0 balance, cannot refund 3,000,000

            Transaction orig =
                    Transaction.createTransfer(
                            "TXN-TRF002",
                            1L,
                            2L,
                            new BigDecimal("3000000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("1000000.00"),
                            new BigDecimal("3000000.00"),
                            "Online",
                            "Transfer");

            when(transactionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(orig));
            when(transactionRepository.existsByReversalOfId(1L)).thenReturn(false);
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account1));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(account2));

            assertThatThrownBy(() -> transactionService.reverseTransaction(1L))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

            assertThat(orig.getStatus()).isEqualTo(TransactionStatus.COMPLETED); // not modified
        }

        @Test
        @DisplayName(
                "resolveCurrentScope returns authenticated principal name or DEFAULT when"
                        + " unauthenticated (R3-10)")
        void resolveCurrentScope_shouldReturnPrincipalNameOrDefault() {
            try {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                assertThat(TransactionService.resolveCurrentScope()).isEqualTo("DEFAULT");

                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(
                                new org.springframework.security.authentication
                                        .UsernamePasswordAuthenticationToken(
                                        "admin",
                                        "N/A",
                                        java.util.List.of(
                                                new org.springframework.security.core.authority
                                                        .SimpleGrantedAuthority("ROLE_ADMIN"))));
                assertThat(TransactionService.resolveCurrentScope()).isEqualTo("admin");
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName(
                "searchTransactions restricts results to owned accounts for CUSTOMER and rejects"
                        + " unowned accountId (AC 4, L2-01)")
        void
                searchTransactions_whenCustomer_shouldRestrictToOwnedAccountsAndRejectForeignAccount() {
            org.springframework.security.oauth2.jwt.Jwt jwt =
                    org.springframework.security.oauth2.jwt.Jwt.withTokenValue("cust-token")
                            .header("alg", "HS256")
                            .subject("2")
                            .claim("username", "cust.an")
                            .claim("role", "CUSTOMER")
                            .claim("customerId", 10L)
                            .issuedAt(Instant.parse("2026-10-14T10:00:00Z"))
                            .expiresAt(Instant.parse("2026-10-14T10:15:00Z"))
                            .build();
            org.springframework.security.oauth2.server.resource.authentication
                            .JwtAuthenticationToken
                    customerAuth =
                            new org.springframework.security.oauth2.server.resource.authentication
                                    .JwtAuthenticationToken(
                                    jwt,
                                    java.util.List.of(
                                            new org.springframework.security.core.authority
                                                    .SimpleGrantedAuthority("ROLE_CUSTOMER")),
                                    "cust.an");

            try {
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(customerAuth);

                // 1. Customer with empty account set returns empty page without DB query
                when(accountRepository.findIdsByCustomerId(10L)).thenReturn(java.util.Set.of());
                PageResponse<TransactionResponse> emptyResult =
                        transactionService.searchTransactions(
                                new TransactionSearchCriteria(null, null), PageRequest.of(0, 20));
                assertThat(emptyResult.content()).isEmpty();
                assertThat(emptyResult.totalElements()).isZero();

                // 2. Customer with owned accounts queries only within owned account IDs
                when(accountRepository.findIdsByCustomerId(10L))
                        .thenReturn(java.util.Set.of(100L, 101L));
                Transaction tx =
                        Transaction.createDeposit(
                                "TXN-CUST-001",
                                100L,
                                new BigDecimal("500000.00"),
                                new BigDecimal("500000.00"),
                                "HN",
                                "Nạp");
                when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                        .thenReturn(new PageImpl<>(List.of(tx), PageRequest.of(0, 20), 1));
                PageResponse<TransactionResponse> ownedResult =
                        transactionService.searchTransactions(
                                new TransactionSearchCriteria(null, null), PageRequest.of(0, 20));
                assertThat(ownedResult.content()).hasSize(1);

                // 3. Customer filtering by owned accountId succeeds
                when(accountRepository.existsByIdAndCustomerId(100L, 10L)).thenReturn(true);
                PageResponse<TransactionResponse> filteredOwned =
                        transactionService.searchTransactions(
                                new TransactionSearchCriteria(100L, null), PageRequest.of(0, 20));
                assertThat(filteredOwned.content()).hasSize(1);

                // 4. Customer filtering by foreign accountId throws AccessDeniedException
                when(accountRepository.existsByIdAndCustomerId(999L, 10L)).thenReturn(false);
                assertThatThrownBy(
                                () ->
                                        transactionService.searchTransactions(
                                                new TransactionSearchCriteria(999L, null),
                                                PageRequest.of(0, 20)))
                        .isInstanceOf(
                                org.springframework.security.access.AccessDeniedException.class);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName(
                "searchTransactions supports multi-filter criteria (status, minAmount, maxAmount,"
                        + " fromDate, toDate) and sorting by fee (AC 1, AC 2, L2-03, L2-04)")
        void searchTransactions_withAdvancedFiltersAndFeeSort_shouldReturnPagedResults() {
            Transaction tx =
                    Transaction.createTransfer(
                            "TXN-TRF-3301",
                            1L,
                            2L,
                            new BigDecimal("2500000.00"),
                            new BigDecimal("2000.00"),
                            new BigDecimal("7498000.00"),
                            new BigDecimal("12500000.00"),
                            "HCMC",
                            "Chuyển khoản tháng 9");
            Pageable feeSortedPageable = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("fee")));
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(tx), feeSortedPageable, 1));

            TransactionSearchCriteria criteria =
                    new TransactionSearchCriteria(
                            1L,
                            TransactionType.TRANSFER,
                            TransactionStatus.COMPLETED,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("5000000.00"),
                            LocalDate.of(2026, 9, 1),
                            LocalDate.of(2026, 9, 30));

            PageResponse<TransactionResponse> result =
                    transactionService.searchTransactions(criteria, feeSortedPageable);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).referenceNo()).isEqualTo("TXN-TRF-3301");
            assertThat(result.content().get(0).fee()).isEqualByComparingTo("2000.00");
        }

        @Test
        @DisplayName(
                "searchTransactions rejects negative minAmount/maxAmount, minAmount > maxAmount,"
                        + " and fromDate > toDate with VALIDATION_FAILED (AC 2)")
        void searchTransactions_whenInvalidFilterRanges_shouldThrowValidationFailed() {
            Pageable pageable = PageRequest.of(0, 10);

            assertThatThrownBy(
                            () ->
                                    transactionService.searchTransactions(
                                            new TransactionSearchCriteria(
                                                    null,
                                                    null,
                                                    null,
                                                    new BigDecimal("-1.00"),
                                                    null,
                                                    null,
                                                    null),
                                            pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(
                            () ->
                                    transactionService.searchTransactions(
                                            new TransactionSearchCriteria(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    new BigDecimal("-100.00"),
                                                    null,
                                                    null),
                                            pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(
                            () ->
                                    transactionService.searchTransactions(
                                            new TransactionSearchCriteria(
                                                    null,
                                                    null,
                                                    null,
                                                    new BigDecimal("5000000.00"),
                                                    new BigDecimal("1000000.00"),
                                                    null,
                                                    null),
                                            pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(
                            () ->
                                    transactionService.searchTransactions(
                                            new TransactionSearchCriteria(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    LocalDate.of(2026, 10, 15),
                                                    LocalDate.of(2026, 10, 1)),
                                            pageable))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName(
                "executeScheduledTransaction validates same account, inactive source/target, and"
                        + " unsupported type before mutating balances (L2-07)")
        void executeScheduledTransaction_edgeCases_shouldValidateBeforeMutating() {
            assertThatThrownBy(
                            () ->
                                    transactionService.executeScheduledTransaction(
                                            TransactionType.TRANSFER,
                                            "REC-1-20261015",
                                            1L,
                                            10L,
                                            10L,
                                            new BigDecimal("100000.00"),
                                            "HQ",
                                            "Same account"))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);

            assertThatThrownBy(
                            () ->
                                    transactionService.executeScheduledTransaction(
                                            TransactionType.WITHDRAWAL,
                                            "REC-2-20261015",
                                            2L,
                                            10L,
                                            null,
                                            new BigDecimal("100000.00"),
                                            "HQ",
                                            "Unsupported"))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            Account activeSource =
                    createAccount(
                            1L,
                            new BigDecimal("5000000.00"),
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"));
            Account lockedTarget =
                    createAccount(
                            2L,
                            new BigDecimal("1000000.00"),
                            new BigDecimal("20000000.00"),
                            new BigDecimal("50000000.00"));
            lockedTarget.changeStatus(
                    com.example.corebanking.account.domain.AccountStatus.LOCKED, Instant.now());

            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeSource));
            when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(lockedTarget));

            assertThatThrownBy(
                            () ->
                                    transactionService.executeScheduledTransaction(
                                            TransactionType.TRANSFER,
                                            "REC-3-20261015",
                                            3L,
                                            1L,
                                            2L,
                                            new BigDecimal("500000.00"),
                                            "HQ",
                                            "Target locked"))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
            assertThat(activeSource.getBalance()).isEqualByComparingTo("5000000.00");
        }
    }
}
