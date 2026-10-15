package com.example.corebanking.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    @DisplayName("Constructor initializes active account with zero balance and VND currency")
    void constructor_shouldInitializeValidAccount() {
        LocalDate openedDate = LocalDate.of(2026, 10, 9);
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        openedDate);

        assertThat(account.getAccountNumber()).isEqualTo("100000000016");
        assertThat(account.getCustomerId()).isEqualTo(1L);
        assertThat(account.getCurrency()).isEqualTo("VND");
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getOpenedDate()).isEqualTo(openedDate);
        assertThat(account.getTransactionLimit()).isEqualByComparingTo("50000000.00");
        assertThat(account.getDailyLimit()).isEqualByComparingTo("100000000.00");
        assertThat(account.getVersion()).isEqualTo(0L);
        assertThat(account.isActive()).isTrue();
        assertThat(account.isClosed()).isFalse();
    }

    @Test
    @DisplayName("Constructor rejects dailyLimit < transactionLimit or negative limits")
    void constructor_shouldRejectInvalidLimits() {
        LocalDate openedDate = LocalDate.of(2026, 10, 9);

        // dailyLimit < transactionLimit
        assertThatThrownBy(
                        () ->
                                new Account(
                                        "100000000016",
                                        1L,
                                        new BigDecimal("50000000.00"),
                                        new BigDecimal("20000000.00"),
                                        openedDate))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        // transactionLimit <= 0 (including sub-cent 0.004 that rounds to 0.00)
        assertThatThrownBy(
                        () ->
                                new Account(
                                        "100000000016",
                                        1L,
                                        BigDecimal.ZERO,
                                        new BigDecimal("100000000.00"),
                                        openedDate))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                new Account(
                                        "100000000016",
                                        1L,
                                        new BigDecimal("0.004"),
                                        new BigDecimal("100000000.00"),
                                        openedDate))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("updateLimits updates limits when valid")
    void updateLimits_shouldUpdateWhenValid() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        account.updateLimits(new BigDecimal("60000000.00"), new BigDecimal("120000000.00"));

        assertThat(account.getTransactionLimit()).isEqualByComparingTo("60000000.00");
        assertThat(account.getDailyLimit()).isEqualByComparingTo("120000000.00");
    }

    @Test
    @DisplayName("updateLimits throws INVALID_STATUS_TRANSITION (409) when account is closed")
    void updateLimits_shouldThrowWhenAccountIsClosed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        account.close(Instant.now());

        assertThatThrownBy(
                        () ->
                                account.updateLimits(
                                        new BigDecimal("60000000.00"),
                                        new BigDecimal("120000000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION))
                .hasMessageContaining("Cannot update limits on a closed account");
    }

    @Test
    @DisplayName("close() succeeds when balance is zero and transitions to CLOSED")
    void close_shouldSucceedWhenBalanceZero() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        Instant closedAt = Instant.parse("2026-10-09T08:00:00Z");
        account.close(closedAt);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(closedAt);
        assertThat(account.isClosed()).isTrue();
    }

    @Test
    @DisplayName("close() throws ACCOUNT_HAS_BALANCE (409) when balance is greater than zero")
    void close_shouldThrowWhenBalanceGreaterThanZero() throws Exception {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        // Simulate balance > 0
        Field balanceField = Account.class.getDeclaredField("balance");
        balanceField.setAccessible(true);
        balanceField.set(account, new BigDecimal("1500000.00"));

        assertThatThrownBy(() -> account.close(Instant.now()))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_HAS_BALANCE))
                .hasMessageContaining("Account balance must be zero before closing");
    }

    @Test
    @DisplayName("credit() increases balance when account is ACTIVE")
    void credit_whenAccountActive_shouldIncreaseBalance() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        account.credit(com.example.corebanking.common.domain.Money.of("1500000.00"));
        assertThat(account.getBalance()).isEqualByComparingTo("1500000.00");

        account.credit(com.example.corebanking.common.domain.Money.of("500000.00"));
        assertThat(account.getBalance()).isEqualByComparingTo("2000000.00");
    }

    @Test
    @DisplayName("credit() throws VALIDATION_FAILED when amount is zero or negative")
    void credit_whenAmountZeroOrNegative_shouldThrowValidationFailed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        assertThatThrownBy(() -> account.credit(com.example.corebanking.common.domain.Money.zero()))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                account.credit(
                                        com.example.corebanking.common.domain.Money.of(
                                                "-50000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("credit() throws ACCOUNT_NOT_ACTIVE when account is closed")
    void credit_whenAccountClosed_shouldThrowAccountNotActive() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        account.close(Instant.parse("2026-10-09T08:00:00Z"));

        assertThatThrownBy(
                        () ->
                                account.credit(
                                        com.example.corebanking.common.domain.Money.of(
                                                "100000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

    @Test
    @DisplayName("debit() subtracts amount when balance is sufficient")
    void debit_whenSufficientBalance_shouldDeductAmount() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        account.credit(com.example.corebanking.common.domain.Money.of("500000.00"));

        account.debit(com.example.corebanking.common.domain.Money.of("200000.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("300000.00");
    }

    @Test
    @DisplayName("debit() throws INSUFFICIENT_BALANCE when balance is lower than debit amount")
    void debit_whenInsufficientBalance_shouldThrowInsufficientBalance() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        account.credit(com.example.corebanking.common.domain.Money.of("100000.00"));

        assertThatThrownBy(
                        () ->
                                account.debit(
                                        com.example.corebanking.common.domain.Money.of(
                                                "100000.01")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("debit() throws VALIDATION_FAILED when debit amount is zero or negative")
    void debit_whenAmountZeroOrNegative_shouldThrowValidationFailed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        assertThatThrownBy(() -> account.debit(com.example.corebanking.common.domain.Money.zero()))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                account.debit(
                                        com.example.corebanking.common.domain.Money.of("-1000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("debit() throws ACCOUNT_NOT_ACTIVE when account is closed")
    void debit_whenAccountClosed_shouldThrowAccountNotActive() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        account.close(Instant.parse("2026-10-09T08:00:00Z"));

        assertThatThrownBy(
                        () ->
                                account.debit(
                                        com.example.corebanking.common.domain.Money.of("10000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

    @Test
    @DisplayName(
            "changeStatus() supports valid transitions ACTIVE -> LOCKED -> ACTIVE -> CLOSED"
                    + " (L2-05, FR-3)")
    void changeStatus_shouldSupportValidLifecycleTransitions() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));

        Instant t1 = Instant.parse("2026-10-09T09:00:00Z");
        account.changeStatus(AccountStatus.LOCKED, t1);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.isLocked()).isTrue();
        assertThat(account.isActive()).isFalse();
        assertThat(account.getClosedAt()).isNull();

        Instant t2 = Instant.parse("2026-10-09T10:00:00Z");
        account.changeStatus(AccountStatus.ACTIVE, t2);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isActive()).isTrue();
        assertThat(account.isLocked()).isFalse();

        Instant t3 = Instant.parse("2026-10-09T11:00:00Z");
        account.changeStatus(AccountStatus.LOCKED, t3);
        Instant t4 = Instant.parse("2026-10-09T12:00:00Z");
        account.changeStatus(AccountStatus.CLOSED, t4);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.isClosed()).isTrue();
        assertThat(account.getClosedAt()).isEqualTo(t4);
    }

    @Test
    @DisplayName(
            "changeStatus() throws INVALID_STATUS_TRANSITION (409) on self-transition or transition"
                    + " from CLOSED (L2-05)")
    void changeStatus_shouldRejectSelfTransitionAndTransitionsFromClosed() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        Instant now = Instant.parse("2026-10-09T09:00:00Z");

        // Self-transition ACTIVE -> ACTIVE
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.ACTIVE, now))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        // Transition to LOCKED, then self-transition LOCKED -> LOCKED
        account.changeStatus(AccountStatus.LOCKED, now);
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.LOCKED, now))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        // Transition to CLOSED, then reject CLOSED -> ACTIVE, CLOSED -> LOCKED, CLOSED -> CLOSED
        account.changeStatus(AccountStatus.CLOSED, now);
        for (AccountStatus target : AccountStatus.values()) {
            assertThatThrownBy(() -> account.changeStatus(target, now))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessRuleException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Test
    @DisplayName("credit() and debit() throw ACCOUNT_NOT_ACTIVE (422) when account is LOCKED")
    void creditAndDebit_whenAccountLocked_shouldThrowAccountNotActive() {
        Account account =
                new Account(
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 9));
        account.credit(com.example.corebanking.common.domain.Money.of("500000.00"));
        account.changeStatus(AccountStatus.LOCKED, Instant.parse("2026-10-09T09:00:00Z"));

        assertThatThrownBy(
                        () ->
                                account.credit(
                                        com.example.corebanking.common.domain.Money.of(
                                                "100000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));

        assertThatThrownBy(
                        () ->
                                account.debit(
                                        com.example.corebanking.common.domain.Money.of(
                                                "100000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }
}
