package com.example.corebanking.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.infrastructure.TransactionReferenceGenerator;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService =
                new TransactionService(
                        accountRepository, transactionRepository, referenceGenerator);
    }

    private Account createActiveAccount() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        try {
            java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(account, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return account;
    }

    @Test
    @DisplayName("deposit() credits account and persists completed transaction record")
    void deposit_whenAccountActive_shouldIncreaseBalanceAndSaveTransaction() {
        Account account = createActiveAccount();
        DepositRequest request =
                new DepositRequest(1L, new BigDecimal("1500000.00"), "Hà Nội", "Nạp tiền ban đầu");

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
    }

    @Test
    @DisplayName("deposit() throws RESOURCE_NOT_FOUND when account does not exist")
    void deposit_whenAccountNotFound_shouldThrowResourceNotFound() {
        DepositRequest request = new DepositRequest(999L, new BigDecimal("100000.00"), "HN", "Nạp");
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

        DepositRequest request = new DepositRequest(1L, new BigDecimal("100000.00"), "HN", "Nạp");
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.deposit(request))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

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
    @DisplayName("getTransactionById() throws RESOURCE_NOT_FOUND when transaction does not exist")
    void getTransactionById_whenNotFound_shouldThrowResourceNotFound() {
        when(transactionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction not found with identifier: 999");
    }

    @Test
    @DisplayName("getAccountTransactions() throws RESOURCE_NOT_FOUND when account does not exist")
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
