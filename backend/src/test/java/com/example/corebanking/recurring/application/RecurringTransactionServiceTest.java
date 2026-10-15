package com.example.corebanking.recurring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.recurring.api.dto.CreateRecurringTransactionRequest;
import com.example.corebanking.recurring.api.dto.RecurringTransactionResponse;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.example.corebanking.recurring.infrastructure.RecurringAccountClosureGuard;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RecurringTransactionServiceTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant FIXED_NOW =
            ZonedDateTime.of(2026, 10, 15, 10, 0, 0, 0, VN_ZONE).toInstant();
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, VN_ZONE);

    @Mock private RecurringTransactionRepository recurringRepository;
    @Mock private AccountRepository accountRepository;

    private RecurringTransactionService service;

    @BeforeEach
    void setUp() {
        service =
                new RecurringTransactionService(
                        recurringRepository, accountRepository, FIXED_CLOCK);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Account createActiveAccount(Long id, String accountNumber) {
        Account account =
                new Account(
                        accountNumber,
                        1L,
                        new BigDecimal("20000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @Test
    @DisplayName(
            "createRecurringTransaction for TRANSFER should lock accounts in ascending ID order and"
                    + " save ACTIVE instruction")
    void createRecurringTransaction_whenTransfer_shouldLockAccountsInAscendingOrder() {
        Account sourceAccount = createActiveAccount(20L, "100800000026");
        Account targetAccount = createActiveAccount(10L, "100800000018");

        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(targetAccount));
        when(accountRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(sourceAccount));
        when(recurringRepository.save(any(RecurringTransaction.class)))
                .thenAnswer(
                        inv -> {
                            RecurringTransaction r = inv.getArgument(0);
                            ReflectionTestUtils.setField(r, "id", 100L);
                            return r;
                        });

        CreateRecurringTransactionRequest request =
                new CreateRecurringTransactionRequest(
                        RecurringTransactionType.TRANSFER,
                        20L,
                        10L,
                        new BigDecimal("5000000.00"),
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2027, 10, 15),
                        "Hà Nội",
                        "Chuyển khoản tiết kiệm hàng tháng");

        RecurringTransactionResponse response = service.createRecurringTransaction(request);

        InOrder inOrder = inOrder(accountRepository, recurringRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(10L);
        inOrder.verify(accountRepository).findByIdForUpdate(20L);
        inOrder.verify(recurringRepository).save(any(RecurringTransaction.class));

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(response.createdBy()).isEqualTo("admin");
        assertThat(response.nextRunAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName(
            "createRecurringTransaction when account is LOCKED should throw 422 ACCOUNT_NOT_ACTIVE")
    void createRecurringTransaction_whenAccountLocked_shouldThrowAccountNotActive() {
        Account lockedAccount = createActiveAccount(10L, "100800000018");
        lockedAccount.changeStatus(AccountStatus.LOCKED, FIXED_NOW);
        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(lockedAccount));

        CreateRecurringTransactionRequest request =
                new CreateRecurringTransactionRequest(
                        RecurringTransactionType.PAYMENT,
                        10L,
                        null,
                        new BigDecimal("1000000.00"),
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        "Thanh toán hóa đơn");

        assertThatThrownBy(() -> service.createRecurringTransaction(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
        verify(recurringRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRecurringTransaction when account not found should throw 404")
    void createRecurringTransaction_whenAccountNotFound_shouldThrowResourceNotFound() {
        when(accountRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        CreateRecurringTransactionRequest request =
                new CreateRecurringTransactionRequest(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        999L,
                        new BigDecimal("1000000.00"),
                        RecurringFrequency.WEEKLY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> service.createRecurringTransaction(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName(
            "pause, resume, and cancel should transition status and validate active accounts on"
                    + " resume")
    void pauseResumeAndCancel_shouldTransitionStatusAndValidateAccountOnResume() {
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        10L,
                        null,
                        new BigDecimal("2000000.00"),
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        "Thanh toán tiền nhà",
                        "admin",
                        FIXED_CLOCK);
        ReflectionTestUtils.setField(recurring, "id", 55L);

        when(recurringRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(recurring));
        when(recurringRepository.save(any(RecurringTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1. Pause
        RecurringTransactionResponse paused = service.pauseRecurringTransaction(55L);
        assertThat(paused.status()).isEqualTo(RecurringStatus.PAUSED);

        // 2. Resume when account is ACTIVE succeeds
        Account activeAccount = createActiveAccount(10L, "100800000018");
        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(activeAccount));

        RecurringTransactionResponse resumed = service.resumeRecurringTransaction(55L);
        assertThat(resumed.status()).isEqualTo(RecurringStatus.ACTIVE);

        // 3. Cancel
        service.cancelRecurringTransaction(55L);
        assertThat(recurring.getStatus()).isEqualTo(RecurringStatus.CANCELLED);
    }

    @Test
    @DisplayName(
            "searchRecurringTransactions should return PageResponse and reject invalid sort fields"
                    + " or page size > 100")
    @SuppressWarnings("unchecked")
    void searchRecurringTransactions_shouldFilterAndValidateSortAndPageSize() {
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        10L,
                        new BigDecimal("1000000.00"),
                        RecurringFrequency.DAILY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        null,
                        "admin",
                        FIXED_CLOCK);
        ReflectionTestUtils.setField(rec, "id", 1L);

        when(recurringRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rec)));

        PageResponse<RecurringTransactionResponse> page =
                service.searchRecurringTransactions(
                        RecurringStatus.ACTIVE,
                        10L,
                        RecurringTransactionType.DEPOSIT,
                        RecurringFrequency.DAILY,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.asc("nextRunAt"))));
        assertThat(page.content()).hasSize(1);

        // Invalid sort field -> 400 INVALID_SORT_FIELD
        Pageable badSort = PageRequest.of(0, 20, Sort.by("unknownField"));
        assertThatThrownBy(
                        () -> service.searchRecurringTransactions(null, null, null, null, badSort))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SORT_FIELD);

        // Page size > 100 -> 400 VALIDATION_FAILED
        Pageable oversized = PageRequest.of(0, 101);
        assertThatThrownBy(
                        () ->
                                service.searchRecurringTransactions(
                                        null, null, null, null, oversized))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName(
            "RecurringAccountClosureGuard should throw 409 ACCOUNT_HAS_ACTIVE_RECURRING when active"
                    + " recurring exists")
    void recurringAccountClosureGuard_shouldBlockWhenActiveRecurringExists() {
        RecurringAccountClosureGuard guard = new RecurringAccountClosureGuard(recurringRepository);

        when(recurringRepository.existsByAccountIdAndStatus(10L, RecurringStatus.ACTIVE))
                .thenReturn(true);
        assertThatThrownBy(() -> guard.validateCanCloseAccount(10L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_HAS_ACTIVE_RECURRING);

        when(recurringRepository.existsByAccountIdAndStatus(10L, RecurringStatus.ACTIVE))
                .thenReturn(false);
        assertThatCode(() -> guard.validateCanCloseAccount(10L)).doesNotThrowAnyException();
    }
}
