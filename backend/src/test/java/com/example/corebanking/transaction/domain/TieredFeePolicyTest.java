package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.common.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TieredFeePolicy Unit Tests")
class TieredFeePolicyTest {

    private TieredFeePolicy policy;

    @BeforeEach
    void setUp() {
        FeeProperties properties = new FeeProperties();
        // Setup standard tiers according to PRD FR-6 & Story 2.4 ACs
        properties.setWithdrawal(
                List.of(
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("0.00"),
                                new BigDecimal("2000.00"),
                                BigDecimal.ZERO,
                                null),
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("10000000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.02"),
                                new BigDecimal("50000.00"))));

        properties.setTransfer(
                List.of(
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("0.00"), BigDecimal.ZERO, BigDecimal.ZERO, null),
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("500000.00"),
                                new BigDecimal("2000.00"),
                                BigDecimal.ZERO,
                                null),
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("10000000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.02"),
                                new BigDecimal("50000.00"))));

        properties.setDeposit(
                List.of(
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("0.00"), BigDecimal.ZERO, BigDecimal.ZERO, null)));

        properties.setPayment(
                List.of(
                        new FeeProperties.FeeTierConfig(
                                new BigDecimal("0.00"), BigDecimal.ZERO, BigDecimal.ZERO, null)));

        policy = new TieredFeePolicy(properties);
    }

    @Nested
    @DisplayName("TRANSFER Fee Calculation Tests")
    class TransferFeeTests {

        @Test
        @DisplayName("Transfer under 500,000 is free (0.00 VND)")
        void calculateFee_transferBelow500k_shouldBeFree() {
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("499999.99"));
            assertThat(fee).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("Transfer at exactly 500,000 boundary charges flat fee 2,000 VND")
        void calculateFee_transferAt500kBoundary_shouldChargeFlat2000() {
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("500000.00"));
            assertThat(fee).isEqualTo(Money.of("2000.00"));
        }

        @Test
        @DisplayName("Transfer between 500k and 10M charges flat fee 2,000 VND")
        void calculateFee_transferBetween500kAnd10M_shouldChargeFlat2000() {
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("9999999.99"));
            assertThat(fee).isEqualTo(Money.of("2000.00"));
        }

        @Test
        @DisplayName("Transfer at exactly 10M boundary charges 0.02% (2,000 VND)")
        void calculateFee_transferAt10MBoundary_shouldCharge2000() {
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("10000000.00"));
            assertThat(fee).isEqualTo(Money.of("2000.00"));
        }

        @Test
        @DisplayName("Transfer above 10M charges 0.02% rate")
        void calculateFee_transferAbove10M_shouldChargePercentage() {
            // 15,000,000 * 0.0002 = 3,000.00
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("15000000.00"));
            assertThat(fee).isEqualTo(Money.of("3000.00"));
        }

        @Test
        @DisplayName("Transfer with huge amount caps fee at 50,000 VND")
        void calculateFee_transferHugeAmount_shouldCapAt50k() {
            // 300,000,000 * 0.0002 = 60,000.00 -> capped at 50,000.00
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("300000000.00"));
            assertThat(fee).isEqualTo(Money.of("50000.00"));
        }
    }

    @Nested
    @DisplayName("WITHDRAWAL Fee Calculation Tests")
    class WithdrawalFeeTests {

        @Test
        @DisplayName("Withdrawal below 10M charges flat fee 2,000 VND")
        void calculateFee_withdrawalBelow10M_shouldChargeFlat2000() {
            Money fee = policy.calculateFee(TransactionType.WITHDRAWAL, Money.of("5000000.00"));
            assertThat(fee).isEqualTo(Money.of("2000.00"));
        }

        @Test
        @DisplayName("Withdrawal at 10M boundary charges 0.02% (2,000 VND)")
        void calculateFee_withdrawalAt10MBoundary_shouldCharge2000() {
            Money fee = policy.calculateFee(TransactionType.WITHDRAWAL, Money.of("10000000.00"));
            assertThat(fee).isEqualTo(Money.of("2000.00"));
        }

        @Test
        @DisplayName("Withdrawal of 12,000,000 charges 2,400 VND according to PRD FR-6")
        void calculateFee_withdrawal12M_shouldCharge2400() {
            // 12,000,000 * 0.0002 = 2,400.00
            Money fee = policy.calculateFee(TransactionType.WITHDRAWAL, Money.of("12000000.00"));
            assertThat(fee).isEqualTo(Money.of("2400.00"));
        }

        @Test
        @DisplayName("Withdrawal huge amount caps at 50,000 VND")
        void calculateFee_withdrawalHugeAmount_shouldCapAt50k() {
            Money fee = policy.calculateFee(TransactionType.WITHDRAWAL, Money.of("500000000.00"));
            assertThat(fee).isEqualTo(Money.of("50000.00"));
        }
    }

    @Nested
    @DisplayName("DEPOSIT & PAYMENT Fee Calculation Tests")
    class DepositAndPaymentTests {

        @ParameterizedTest
        @CsvSource({
            "DEPOSIT, 1000000.00",
            "DEPOSIT, 50000000.00",
            "PAYMENT, 200000.00",
            "PAYMENT, 10000000.00"
        })
        @DisplayName("DEPOSIT and PAYMENT transactions have zero fee")
        void calculateFee_depositAndPayment_shouldAlwaysBeZero(
                TransactionType type, String amount) {
            Money fee = policy.calculateFee(type, Money.of(amount));
            assertThat(fee).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("REVERSAL transactions are rejected with VALIDATION_FAILED")
        void calculateFee_reversal_shouldThrowValidationFailed() {
            assertThatThrownBy(
                            () ->
                                    policy.calculateFee(
                                            TransactionType.REVERSAL, Money.of("10000000.00")))
                    .isInstanceOf(com.example.corebanking.common.domain.BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(com.example.corebanking.common.domain.ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    @DisplayName("Rounding & Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("RoundingMode.HALF_UP rounds fractional cents accurately")
        void calculateFee_fractionalRate_shouldRoundHalfUp() {
            // 10,001,234.00 * 0.0002 = 2000.2468 -> 2000.25
            Money fee = policy.calculateFee(TransactionType.TRANSFER, Money.of("10001234.00"));
            assertThat(fee).isEqualTo(Money.of("2000.25"));
        }

        @Test
        @DisplayName("calculateFee throws IllegalArgumentException for negative or zero amounts")
        void calculateFee_negativeOrZero_shouldThrowException() {
            assertThatThrownBy(
                            () -> policy.calculateFee(TransactionType.TRANSFER, Money.of("0.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly positive");

            assertThatThrownBy(
                            () ->
                                    policy.calculateFee(
                                            TransactionType.TRANSFER, Money.of("-1000.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly positive");
        }

        @Test
        @DisplayName("calculateFee throws NullPointerException when arguments are null")
        void calculateFee_nullArguments_shouldThrowException() {
            assertThatThrownBy(() -> policy.calculateFee(null, Money.of("1000.00")))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> policy.calculateFee(TransactionType.TRANSFER, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
