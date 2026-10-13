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
}
