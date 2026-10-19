package com.example.corebanking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.AccountStatusHistoryResponse;
import com.example.corebanking.account.api.dto.ChangeAccountStatusRequest;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.AccountStatusHistory;
import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.domain.BalanceTierPolicy;
import com.example.corebanking.account.domain.BalanceTierProperties;
import com.example.corebanking.account.domain.event.AccountStatusChangedEvent;
import com.example.corebanking.account.infrastructure.AccountNumberGenerator;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.account.infrastructure.AccountStatusHistoryRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AccountNumberGenerator accountNumberGenerator;
    @Mock private AccountStatusHistoryRepository statusHistoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private BalanceTierPolicy balanceTierPolicy;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        BalanceTierProperties properties =
                new BalanceTierProperties(new BigDecimal("10000000"), new BigDecimal("100000000"));
        balanceTierPolicy = new BalanceTierPolicy(properties);
        accountService =
                new AccountService(
                        accountRepository,
                        customerRepository,
                        accountNumberGenerator,
                        balanceTierPolicy,
                        clock,
                        statusHistoryRepository,
                        eventPublisher);
    }

    private void setId(Account account, Long id) {
        try {
            Field idField = Account.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(account, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setBalance(Account account, BigDecimal balance) {
        try {
            Field balanceField = Account.class.getDeclaredField("balance");
            balanceField.setAccessible(true);
            balanceField.set(account, balance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setCreatedAt(Account account, Instant createdAt) {
        try {
            Field field = BaseEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(account, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createAccount successfully opens account with 0 balance and LOW tier")
    void createAccount_shouldSucceed() {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        1L, new BigDecimal("10000000.00"), new BigDecimal("50000000.00"));

        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        when(customerRepository.findByIdAndDeletedAtIsNullForUpdate(1L))
                .thenReturn(Optional.of(customer));
        when(accountNumberGenerator.nextAccountNumber()).thenReturn("100000000016");
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(
                        invocation -> {
                            Account a = invocation.getArgument(0);
                            setId(a, 10L);
                            setCreatedAt(a, clock.instant());
                            return a;
                        });

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.accountNumber()).isEqualTo("100000000016");
        assertThat(response.customerId()).isEqualTo(1L);
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.balance()).isEqualByComparingTo("0.00");
        assertThat(response.balanceTier()).isEqualTo(BalanceTier.LOW);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.openedDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("createAccount throws ResourceNotFoundException when customer not found")
    void createAccount_shouldThrowWhenCustomerNotFound() {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        999L, new BigDecimal("10000000.00"), new BigDecimal("50000000.00"));

        when(customerRepository.findByIdAndDeletedAtIsNullForUpdate(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.createAccount(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer not found with identifier: 999");
    }

    @Test
    @DisplayName("createAccount throws BusinessRuleException when daily limit < transaction limit")
    void createAccount_shouldThrowWhenDailyLimitLessThanTransactionLimit() {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        1L, new BigDecimal("50000000.00"), new BigDecimal("10000000.00"));

        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        when(customerRepository.findByIdAndDeletedAtIsNullForUpdate(1L))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> accountService.createAccount(request))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("must be greater than or equal to transaction limit");
    }

    @Test
    @DisplayName("getAccountById returns account with classified balance tier")
    void getAccountById_shouldSucceed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);
        setBalance(account, new BigDecimal("25000000.00"));
        setCreatedAt(account, clock.instant());

        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccountById(10L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.balance()).isEqualByComparingTo("25000000.00");
        assertThat(response.balanceTier()).isEqualTo(BalanceTier.MEDIUM);
    }

    @Test
    @DisplayName("getAccountById throws ResourceNotFoundException when account not found")
    void getAccountById_shouldThrowWhenNotFound() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account not found with identifier: 999");
    }

    @Test
    @DisplayName("searchAccounts filters accounts and returns page response")
    void searchAccounts_shouldReturnPage() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);
        setCreatedAt(account, clock.instant());

        PageRequest pageRequest = PageRequest.of(0, 20, Sort.by("id").ascending());
        when(accountRepository.findAll(any(Specification.class), eq(pageRequest)))
                .thenReturn(new PageImpl<>(List.of(account), pageRequest, 1));

        PageResponse<AccountResponse> result =
                accountService.searchAccounts(
                        1L, AccountStatus.ACTIVE, "100000000016", pageRequest);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).accountNumber()).isEqualTo("100000000016");
        assertThat(result.totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("searchAccounts throws BusinessRuleException when sort field is invalid")
    void searchAccounts_shouldThrowWhenInvalidSortField() {
        PageRequest pageRequest = PageRequest.of(0, 20, Sort.by("hackedField").ascending());

        assertThatThrownBy(() -> accountService.searchAccounts(null, null, null, pageRequest))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_SORT_FIELD));
    }

    @Test
    @DisplayName("searchAccounts throws BusinessRuleException when page size exceeds 100")
    void searchAccounts_shouldThrowWhenPageSizeExceeds100() {
        PageRequest pageRequest = PageRequest.of(0, 101, Sort.by("id").ascending());

        assertThatThrownBy(() -> accountService.searchAccounts(null, null, null, pageRequest))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("updateLimits updates transaction and daily limits on valid version")
    void updateLimits_shouldSucceed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);
        setCreatedAt(account, clock.instant());

        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAccountLimitsRequest updateRequest =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("20000000.00"), new BigDecimal("80000000.00"), 0L);

        AccountResponse response = accountService.updateLimits(10L, updateRequest);

        assertThat(response.transactionLimit()).isEqualByComparingTo("20000000.00");
        assertThat(response.dailyLimit()).isEqualByComparingTo("80000000.00");
        verify(accountRepository).saveAndFlush(account);
    }

    @Test
    @DisplayName("updateLimits throws CONCURRENT_MODIFICATION on version mismatch")
    void updateLimits_shouldThrowOnVersionMismatch() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);

        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        UpdateAccountLimitsRequest updateRequest =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("20000000.00"),
                        new BigDecimal("80000000.00"),
                        5L); // Account version is 0L

        assertThatThrownBy(() -> accountService.updateLimits(10L, updateRequest))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    @DisplayName(
            "closeAccount closes account with zero balance under pessimistic lock and records"
                    + " status history")
    void closeAccount_shouldSucceedWhenBalanceZero() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);

        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        accountService.closeAccount(10L);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(clock.instant());
        verify(accountRepository).save(account);
        verify(statusHistoryRepository).save(any(AccountStatusHistory.class));
        verify(eventPublisher).publishEvent(any(AccountStatusChangedEvent.class));
    }

    @Test
    @DisplayName("closeAccount throws ACCOUNT_HAS_BALANCE when balance > 0")
    void closeAccount_shouldThrowWhenBalancePositive() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);
        setBalance(account, new BigDecimal("100000.00"));

        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.closeAccount(10L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_HAS_BALANCE))
                .hasMessageContaining("Account balance must be zero before closing");
    }

    @Test
    @DisplayName(
            "getAccountsByCustomerId returns customer accounts ordered by id desc with balanceTier"
                    + " and optional status filter (AC 2)")
    void getAccountsByCustomerId_shouldReturnMappedAccountsAndSupportStatusFilter() {
        Account account =
                new Account(
                        "100000000016",
                        10L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 101L);
        setBalance(account, new BigDecimal("75000000.00"));

        when(accountRepository.findByCustomerIdOrderByIdDesc(10L)).thenReturn(List.of(account));
        when(accountRepository.findByCustomerIdAndStatusOrderByIdDesc(10L, AccountStatus.ACTIVE))
                .thenReturn(List.of(account));

        List<AccountResponse> all = accountService.getAccountsByCustomerId(10L, null);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).id()).isEqualTo(101L);
        assertThat(all.get(0).balanceTier()).isEqualTo(BalanceTier.MEDIUM);

        List<AccountResponse> activeOnly =
                accountService.getAccountsByCustomerId(10L, AccountStatus.ACTIVE);
        assertThat(activeOnly).hasSize(1);
        assertThat(activeOnly.get(0).status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName(
            "changeStatus transitions ACTIVE -> LOCKED, saves AccountStatusHistory, and publishes"
                    + " AccountStatusChangedEvent (L2-05)")
    void changeStatus_shouldTransitionAndRecordHistoryAndPublishEvent() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 9));
        setId(account, 10L);
        setCreatedAt(account, clock.instant());

        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        ChangeAccountStatusRequest request =
                new ChangeAccountStatusRequest(
                        AccountStatus.LOCKED, "Phong tỏa tạm thời để xác minh");

        AccountResponse response = accountService.changeStatus(10L, request);

        assertThat(response.status()).isEqualTo(AccountStatus.LOCKED);

        ArgumentCaptor<AccountStatusHistory> histCaptor =
                ArgumentCaptor.forClass(AccountStatusHistory.class);
        verify(statusHistoryRepository).save(histCaptor.capture());
        AccountStatusHistory savedHist = histCaptor.getValue();
        assertThat(savedHist.getAccountId()).isEqualTo(10L);
        assertThat(savedHist.getFromStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(savedHist.getToStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(savedHist.getReason()).isEqualTo("Phong tỏa tạm thời để xác minh");
        assertThat(savedHist.getChangedAt()).isEqualTo(clock.instant());

        ArgumentCaptor<AccountStatusChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(AccountStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AccountStatusChangedEvent event = eventCaptor.getValue();
        assertThat(event.accountId()).isEqualTo(10L);
        assertThat(event.customerId()).isEqualTo(1L);
        assertThat(event.fromStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(event.toStatus()).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    @DisplayName(
            "getStatusHistory returns history entries ordered by changedAt desc, id desc and throws"
                    + " 404 when account missing (L2-05)")
    void getStatusHistory_shouldReturnHistoryOrThrow404() {
        when(accountRepository.existsById(10L)).thenReturn(true);
        AccountStatusHistory h2 =
                new AccountStatusHistory(
                        10L,
                        AccountStatus.ACTIVE,
                        AccountStatus.LOCKED,
                        "Khóa tài khoản",
                        "admin",
                        clock.instant());
        AccountStatusHistory h1 =
                new AccountStatusHistory(
                        10L,
                        null,
                        AccountStatus.ACTIVE,
                        "Account opened",
                        "admin",
                        clock.instant().minusSeconds(60));
        when(statusHistoryRepository.findByAccountIdOrderByChangedAtDescIdDesc(10L))
                .thenReturn(List.of(h2, h1));

        List<AccountStatusHistoryResponse> history = accountService.getStatusHistory(10L);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).fromStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(history.get(0).toStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(history.get(1).fromStatus()).isNull();
        assertThat(history.get(1).toStatus()).isEqualTo(AccountStatus.ACTIVE);

        when(accountRepository.existsById(999L)).thenReturn(false);
        assertThatThrownBy(() -> accountService.getStatusHistory(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account not found with identifier: 999");
    }
}
