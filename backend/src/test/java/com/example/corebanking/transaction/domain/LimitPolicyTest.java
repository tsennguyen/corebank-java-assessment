package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LimitPolicy Unit Tests")
class LimitPolicyTest {

    private LimitPolicy limitPolicy;

    @BeforeEach
    void setUp() {
        LimitProvider limitProvider = new AccountLimitProvider();
        limitPolicy = new LimitPolicy(limitProvider);
    }

    private Account createAccountWithLimits(BigDecimal transactionLimit, BigDecimal dailyLimit) {
        return new Account(
                "100000000001", 1L, transactionLimit, dailyLimit, LocalDate.of(2026, 10, 10));
    }

    @Nested
    @DisplayName("Per-Transaction Limit Validation")
    class TransactionLimitTests {

        @Test
        @DisplayName("validateLimits passes when amount is strictly below per-transaction limit")
        void validateLimits_whenAmountBelowTransactionLimit_shouldPass() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatCode(
                            () ->
                                    limitPolicy.validateLimits(
                                            account, Money.of("49999999.99"), Money.of("0.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateLimits passes when amount is exactly equal to per-transaction limit")
        void validateLimits_whenAmountEqualsTransactionLimit_shouldPass() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatCode(
                            () ->
                                    limitPolicy.validateLimits(
                                            account, Money.of("50000000.00"), Money.of("0.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName(
                "validateLimits throws LIMIT_EXCEEDED when amount exceeds per-transaction limit")
        void validateLimits_whenAmountExceedsTransactionLimit_shouldThrowBusinessRuleException() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatThrownBy(
                            () ->
                                    limitPolicy.validateLimits(
                                            account, Money.of("50000000.01"), Money.of("0.00")))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex -> {
                                BusinessRuleException bre = (BusinessRuleException) ex;
                                assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                                assertThat(bre.getMessage())
                                        .contains("exceeds per-transaction limit of 50000000.00");
                            });
        }
    }

    @Nested
    @DisplayName("Daily Aggregate Limit Validation")
    class DailyLimitTests {

        @Test
        @DisplayName(
                "validateLimits passes when total daily spending is strictly below daily limit")
        void validateLimits_whenDailyTotalBelowDailyLimit_shouldPass() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatCode(
                            () ->
                                    limitPolicy.validateLimits(
                                            account,
                                            Money.of("30000000.00"),
                                            Money.of("60000000.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateLimits passes when total daily spending exactly equals daily limit")
        void validateLimits_whenDailyTotalEqualsDailyLimit_shouldPass() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatCode(
                            () ->
                                    limitPolicy.validateLimits(
                                            account,
                                            Money.of("40000000.00"),
                                            Money.of("60000000.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName(
                "validateLimits throws DAILY_LIMIT_EXCEEDED when total daily spending exceeds daily"
                        + " limit")
        void validateLimits_whenDailyTotalExceedsDailyLimit_shouldThrowBusinessRuleException() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatThrownBy(
                            () ->
                                    limitPolicy.validateLimits(
                                            account,
                                            Money.of("40000000.01"),
                                            Money.of("60000000.00")))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(
                            ex -> {
                                BusinessRuleException bre = (BusinessRuleException) ex;
                                assertThat(bre.getErrorCode())
                                        .isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED);
                                assertThat(bre.getMessage())
                                        .contains("exceeds daily limit of 100000000.00");
                            });
        }
    }

    @Nested
    @DisplayName("Edge Cases & Null Validation")
    class EdgeCasesAndNullTests {

        @Test
        @DisplayName("validateLimits passes when account has null limits (unlimited)")
        void validateLimits_whenAccountLimitsNull_shouldPass() {
            LimitPolicy customPolicy = new LimitPolicy(acc -> new AccountLimits(null, null));
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatCode(
                            () ->
                                    customPolicy.validateLimits(
                                            account,
                                            Money.of("9999999999.00"),
                                            Money.of("8888888888.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateLimits throws NullPointerException when account is null")
        void validateLimits_whenAccountNull_shouldThrowNpe() {
            assertThatThrownBy(
                            () ->
                                    limitPolicy.validateLimits(
                                            null, Money.of("1000000.00"), Money.of("0.00")))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("account must not be null");
        }

        @Test
        @DisplayName("validateLimits throws NullPointerException when amount is null")
        void validateLimits_whenAmountNull_shouldThrowNpe() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatThrownBy(() -> limitPolicy.validateLimits(account, null, Money.of("0.00")))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("amount must not be null");
        }

        @Test
        @DisplayName("validateLimits throws NullPointerException when currentDailySpent is null")
        void validateLimits_whenCurrentDailySpentNull_shouldThrowNpe() {
            Account account =
                    createAccountWithLimits(
                            new BigDecimal("50000000.00"), new BigDecimal("100000000.00"));

            assertThatThrownBy(
                            () -> limitPolicy.validateLimits(account, Money.of("1000000.00"), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("currentDailySpent must not be null");
        }
    }
}
